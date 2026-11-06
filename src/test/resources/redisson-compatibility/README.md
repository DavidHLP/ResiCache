# Legacy Redisson codec fixtures

These are synthetic strings, not user data. Redisson 3.50.0 wrote them with
its unmodified default `SnappyCodec` and `Kryo5Codec` constructors. The short
value is `resicache-pr47-legacy-codec-payload:你好`; the multichunk value repeats
that string 3,000 times. Each file was successfully decoded on the writer
version before the differential reader check.

SHA-256:

- `SnappyCodec-short.bin`: `9fc7282fd96f1be03f787f58cb98b34430f21dab8ab1eed3906df832550b76fb`
- `SnappyCodec-multichunk.bin`: `08bb9f1630b5337dd8421ad7ffbd7d6bb7b8c74e225bea2a8d8965a4ef7bc33f`
- `Kryo5Codec-short.bin`: `2b07de67988cf94ecfc1c6dc7e779df0a614e4f8126e583ab4402e0285746850`
- `Kryo5Codec-multichunk.bin`: `fb6e5391c6a0fc63462998bf17310bb64e304497f2c2538373fb9d32bace6e4e`

The tests read fixed bytes, rather than generating them using the candidate
reader. Do not regenerate fixtures during a dependency update: that would
replace the compatibility check with a same-version round trip. These samples
do not establish compatibility for application classes or custom serializers.
