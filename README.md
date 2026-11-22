# Facet

Facet compresses binary64 time series losslessly by selecting reversible integer
views and segment boundaries using the encoded bit cost.

Manuscript **Facet: Lossless Compression of Floating-Point Time Series with Segment-Wise Reversible Integer Views**

## Build

Use Linux or WSL with Bash, JDK 21, and Python 3. Put both Java executables on
the command path. The codec uses only the Java standard library.

~~~sh
bash scripts/build.sh
~~~

## Check correctness

~~~sh
python3 scripts/check_correctness.py
~~~

The check compares decoded values bit for bit with the inputs, uses an
independent bitstream decoder, and checks the planned costs against exhaustive
enumeration for small blocks. It prints the comparison counts and
`CORRECTNESS_PASS` on success.

## Run Facet

The demo accepts a file of little-endian IEEE 754 binary64 values or a
`.csv` file with one number per line. It reports the total encoded bytes for
the block packets and the elapsed encoding and decoding times in milliseconds.
The binary input and output interface preserves raw bits, including NaN payloads
and signed zero.

~~~sh
java -cp build/classes FacetTool demo data/tiny.csv
~~~

The first four fields are `values=40 blocks=1 packet_bytes=19 restored_values=40`.
The timing fields vary by run.

For the public [City-temp dataset](https://raw.githubusercontent.com/Spatio-Temporal-Lab/elf/386d69348e6c30475761d3519ee0bfe10ed473d3/src/test/resources/ElfTestData/City-temp.csv), download `City-temp.csv` and run:

~~~sh
java -cp build/classes FacetTool demo City-temp.csv
~~~

This repository accompanies the manuscript under review and contains the implementation of the proposed method with a correctness check. The complete experimental scripts will be released after publication.
