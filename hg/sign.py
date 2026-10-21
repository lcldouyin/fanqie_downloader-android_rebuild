"""Pure-Python contract for the Fanqie native signing service.

The native algorithm is not yet recovered. This module deliberately refuses
to fabricate signature values; it provides the observed request/header
contract and validates a complete set of externally supplied results.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence


SIGNATURE_HEADER_NAMES = (
    "X-Argus",
    "X-Gorgon",
    "X-Helios",
    "X-Khronos",
    "X-Ladon",
    "X-Medusa",
    "X-Neptune",
    "X-Soter",
)

NATIVE_ALGORITHM_RECOVERED = False


class NativeSignatureUnavailable(RuntimeError):
    """Raised when asked to sign before the native algorithm is recovered."""


def parse_header_pairs(headers: str) -> list[tuple[str, str]]:
    """Parse the native API's CRLF-separated name/value input format."""
    if not headers:
        return []
    lines = headers.split("\r\n")
    if len(lines) % 2:
        raise ValueError("Headers must contain CRLF-delimited name/value pairs")

    pairs: list[tuple[str, str]] = []
    for index in range(0, len(lines), 2):
        name, value = lines[index], lines[index + 1]
        if not name or any(char in name for char in "\r\n:"):
            raise ValueError(f"Invalid header name at pair {index // 2}")
        if "\r" in value or "\n" in value:
            raise ValueError(f"Invalid header value at pair {index // 2}")
        pairs.append((name, value))
    return pairs


def format_header_pairs(pairs: Sequence[tuple[str, str]]) -> str:
    """Serialize header pairs in the format accepted by the native API."""
    lines: list[str] = []
    for index, pair in enumerate(pairs):
        if len(pair) != 2:
            raise ValueError(f"Header pair {index} must contain a name and value")
        name, value = pair
        if not isinstance(name, str) or not isinstance(value, str):
            raise TypeError(f"Header pair {index} must contain strings")
        if not name or any(char in name for char in "\r\n:"):
            raise ValueError(f"Invalid header name at pair {index}")
        if "\r" in value or "\n" in value:
            raise ValueError(f"Invalid header value at pair {index}")
        lines.extend((name, value))
    return "\r\n".join(lines)


def validate_signature_fields(fields: Mapping[str, str]) -> dict[str, str]:
    """Validate all eight observed native output fields and return canonical order."""
    if any(not isinstance(name, str) for name in fields):
        raise TypeError("Signature header names must be strings")
    supplied = {name.casefold(): value for name, value in fields.items()}
    if len(supplied) != len(fields):
        raise ValueError("Duplicate signature header names are not allowed")
    required = {name.casefold() for name in SIGNATURE_HEADER_NAMES}
    missing = [
        name for name in SIGNATURE_HEADER_NAMES if name.casefold() not in supplied
    ]
    extra = sorted(
        name for name in fields if name.casefold() not in required
    )
    if missing or extra:
        details = []
        if missing:
            details.append("missing: " + ", ".join(missing))
        if extra:
            details.append("unexpected: " + ", ".join(extra))
        raise ValueError("Invalid signature fields (" + "; ".join(details) + ")")

    canonical: dict[str, str] = {}
    for name in SIGNATURE_HEADER_NAMES:
        value = supplied[name.casefold()]
        if not isinstance(value, str) or not value:
            raise ValueError(f"{name} must be a non-empty string")
        if "\r" in value or "\n" in value:
            raise ValueError(f"{name} must not contain line breaks")
        canonical[name] = value
    return canonical


def extract_signature_fields(headers: str) -> dict[str, str]:
    """Extract and validate the complete signature set from CRLF header pairs."""
    fields = {
        name: value
        for name, value in parse_header_pairs(headers)
        if name.casefold() in {item.casefold() for item in SIGNATURE_HEADER_NAMES}
    }
    return validate_signature_fields(fields)


def append_signature_fields(
    headers: str,
    signature_fields: Mapping[str, str],
) -> str:
    """Replace existing signature headers and append a complete validated set."""
    pairs = parse_header_pairs(headers)
    signatures = validate_signature_fields(signature_fields)
    signature_names = {name.casefold() for name in SIGNATURE_HEADER_NAMES}
    retained = [
        (name, value)
        for name, value in pairs
        if name.casefold() not in signature_names
    ]
    retained.extend(signatures.items())
    return format_header_pairs(retained)


def sign_request(
    url: str,
    headers: str,
    body: bytes | str | None = None,
) -> str:
    """Signing entry point reserved for the fully recovered pure-Python algorithm."""
    if not isinstance(url, str) or not url:
        raise ValueError("url must be a non-empty string")
    if not isinstance(headers, str):
        raise TypeError("headers must be a CRLF-delimited string")
    if body is not None and not isinstance(body, (bytes, str)):
        raise TypeError("body must be bytes, str, or None")
    parse_header_pairs(headers)
    raise NativeSignatureUnavailable(
        "The native signer is not yet reimplemented: REA identifies "
        "0x168c80 as a wrapper and the 0x15a890 target remains inside a "
        "44,248-byte obfuscated function with bad-instruction warnings and "
        "truncated value-flow evidence. No signature values were generated."
    )


def self_test() -> None:
    input_headers = "Accept\r\napplication/json\r\nCookie\r\na=b"
    fields = {name: f"value-{index}" for index, name in enumerate(SIGNATURE_HEADER_NAMES)}
    expected = input_headers + "\r\n" + format_header_pairs(
        list(fields.items())
    )
    assert append_signature_fields(input_headers, fields) == expected
    assert parse_header_pairs(format_header_pairs(parse_header_pairs(input_headers))) == [
        ("Accept", "application/json"),
        ("Cookie", "a=b"),
    ]
    assert extract_signature_fields(expected) == fields

    try:
        validate_signature_fields({"X-Khronos": "1700000000"})
    except ValueError:
        pass
    else:
        raise AssertionError("Incomplete signature fields must be rejected")

    try:
        sign_request("https://example.invalid/", input_headers)
    except NativeSignatureUnavailable:
        pass
    else:
        raise AssertionError("Unrecovered native signing must fail explicitly")


if __name__ == "__main__":
    self_test()
    print("Python signer contract self-test passed")
