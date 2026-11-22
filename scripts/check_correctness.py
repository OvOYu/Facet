"""Check the main codec against independent references."""
from pathlib import Path
import json
import struct
import subprocess
import time

from reference_decode import decode


ROOT = Path(__file__).resolve().parents[1]
JAVA = ["java", "-cp", "build/classes"]


def run(*args):
    result = subprocess.run(
        [*JAVA, *args], cwd=ROOT, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, text=True, check=True, timeout=180,
    )
    return result.stdout.strip()


def main():
    packet_dir = ROOT / "build" / ("check-" + str(time.time_ns()))
    summary = json.loads(run("Correctness", str(packet_dir)))
    packets = 0
    comparisons = 0
    for packet in sorted(packet_dir.glob("*.packet")):
        raw = packet.with_suffix(".input").read_bytes()
        expected = [value for (value,) in struct.iter_unpack("<Q", raw)]
        if decode(packet.read_bytes()) != expected:
            raise AssertionError("independent decoder mismatch for " + packet.name)
        packets += 1
        comparisons += len(expected)

    if summary["cases"] != packets or summary["packets"] != packets:
        raise AssertionError("incomplete packet coverage")
    if summary["raw_bit_comparisons"] != comparisons or summary["mismatches"] != 0:
        raise AssertionError("incomplete raw-bit coverage")
    if summary["brute_force_plans"] != 32:
        raise AssertionError("incomplete brute-force planning coverage")
    summary.update(independent_packets=packets, independent_raw_bit_comparisons=comparisons)

    expected_tiny = (ROOT / "data/tiny.expected").read_text(encoding="utf-8").strip()
    actual_tiny = run("FacetTool", "demo", "data/tiny.csv").split(" encode_ms=", 1)[0]
    if actual_tiny != expected_tiny:
        raise AssertionError("tiny input summary: " + actual_tiny)

    print(json.dumps(summary))
    print("CORRECTNESS_PASS")


if __name__ == "__main__":
    main()
