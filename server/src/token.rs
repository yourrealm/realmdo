//! Bearer tokens: random, shown once, stored as a SHA-256 hash.

use sha2::{Digest, Sha256};
use std::io::Read;

const PREFIX: &str = "rdo_";
/// The `/public/` URL secret's prefix, so the two never look alike.
pub const PUBLIC_PREFIX: &str = "pub_";
const BYTES: usize = 32;

/// A fresh token: `rdo_` + 64 hex chars from the OS random source.
pub fn generate() -> std::io::Result<String> {
    generate_with_prefix(PREFIX)
}

pub fn generate_with_prefix(prefix: &str) -> std::io::Result<String> {
    let mut buf = [0u8; BYTES];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut buf)?;
    Ok(format!("{prefix}{}", hex(&buf)))
}

/// Enough of the token to tell entries apart in a list; never enough to use.
pub fn display_prefix(token: &str) -> String {
    token.chars().take(PREFIX.len() + 8).collect()
}

pub fn hash(token: &str) -> String {
    hex(Sha256::digest(token.as_bytes()).as_slice())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_fresh_and_well_formed() {
        let a = generate().unwrap();
        let b = generate().unwrap();
        assert_ne!(a, b);
        assert_eq!(a.len(), PREFIX.len() + BYTES * 2);
        assert!(a.starts_with(PREFIX));
        assert_eq!(display_prefix(&a).len(), 12);
        assert_eq!(hash(&a), hash(&a));
        assert_ne!(hash(&a), hash(&b));
        assert_eq!(hash("").len(), 64);
        assert!(
            generate_with_prefix(PUBLIC_PREFIX)
                .unwrap()
                .starts_with("pub_")
        );
    }
}
