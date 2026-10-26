//! The `/public/` URL secret: random, shown once, stored as a SHA-256 hash.

use sha2::{Digest, Sha256};
use std::io::Read;

const PREFIX: &str = "pub_";
const BYTES: usize = 32;

/// A fresh secret: `pub_` + 64 hex chars from the OS random source.
pub fn generate() -> std::io::Result<String> {
    let mut buf = [0u8; BYTES];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut buf)?;
    Ok(format!("{PREFIX}{}", hex(&buf)))
}

pub fn hash(secret: &str) -> String {
    hex(Sha256::digest(secret.as_bytes()).as_slice())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secrets_are_fresh_and_well_formed() {
        let a = generate().unwrap();
        let b = generate().unwrap();
        assert_ne!(a, b);
        assert_eq!(a.len(), PREFIX.len() + BYTES * 2);
        assert!(a.starts_with(PREFIX));
        assert_eq!(hash(&a), hash(&a));
        assert_ne!(hash(&a), hash(&b));
        assert_eq!(hash("").len(), 64);
    }
}
