# Pinned AAPT2 proto sources

Pinned AOSP revision: `02d8919614b7920aa2c5c3820be77bba8c54846f` (2023-01-14).

Primary source:
`https://android.googlesource.com/platform/frameworks/base/+/02d8919614b7920aa2c5c3820be77bba8c54846f/tools/aapt2/`

The revision and file bytes were resolved and verified on 2026-10-07 through the
official mirror at
`https://github.com/aosp-mirror/platform_frameworks_base/tree/02d8919614b7920aa2c5c3820be77bba8c54846f/tools/aapt2`.
The `main` revision was also checked; `Configuration.proto` matched, while
`Resources.proto` had later flag-related fields and is therefore not used as the
pin.

| Vendored file | Source path at pin | SHA-256 (vendored, LF) | Upstream SHA-256 (LF) |
| --- | --- | --- | --- |
| `Configuration.proto` | `tools/aapt2/Configuration.proto` | `09A7161CF69C07BD5DE13DE5EA2E969888F37773070E060CCBF6FB3067AF751C` | `09A7161CF69C07BD5DE13DE5EA2E969888F37773070E060CCBF6FB3067AF751C` |
| `Resources.proto` | `tools/aapt2/Resources.proto` | `944FCDB97EB031C2275F8F424BB07C668F0FA502F2CD6D5A621158F598A2EB1D` | `C7E78831C26FAA83A905083137C786D6DC6ADCEDF9D91234827EA49DBCD61F70` |

`Configuration.proto` is byte-for-byte identical at the pinned revision.
For `Resources.proto`, CRLF was normalized to LF and only the sibling import
`frameworks/base/tools/aapt2/Configuration.proto` was rewritten to
`Configuration.proto`; after those two mechanical changes the content is
byte-for-byte identical to the pinned upstream file.

## License

Both files are from the Android Open Source Project and retain their original
Apache License 2.0 copyright and license headers. See
`https://www.apache.org/licenses/LICENSE-2.0`.
