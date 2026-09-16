//! Calendar days as integers (days since 1970-01-01). Due dates are days, not
//! instants, and day arithmetic is all urgency needs, so no date crate.

/// Days since the epoch for a proleptic Gregorian date (Howard Hinnant's
/// `days_from_civil`).
pub fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as u64;
    let mp = (u64::from(m) + 9) % 12;
    let doy = (153 * mp + 2) / 5 + u64::from(d) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe as i64 - 719_468
}

/// Parses `YYYY-MM-DD` into days since the epoch. Rejects impossible dates.
pub fn parse_day(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
        return None;
    }
    let y = i64::from(digits(&s[0..4])?);
    let m = digits(&s[5..7])?;
    let d = digits(&s[8..10])?;
    if !(1..=12).contains(&m) || d == 0 || d > days_in_month(y, m) {
        return None;
    }
    Some(days_from_civil(y, m, d))
}

fn digits(s: &str) -> Option<u32> {
    if s.bytes().all(|c| c.is_ascii_digit()) {
        s.parse().ok()
    } else {
        None
    }
}

fn days_in_month(y: i64, m: u32) -> u32 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ if y % 4 == 0 && (y % 100 != 0 || y % 400 == 0) => 29,
        _ => 28,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_and_neighbours() {
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(days_from_civil(1969, 12, 31), -1);
        assert_eq!(days_from_civil(2000, 3, 1), 11_017);
        assert_eq!(days_from_civil(2026, 8, 28), 20_693);
    }

    #[test]
    fn parses_valid_days_only() {
        assert_eq!(parse_day("2026-08-28"), Some(days_from_civil(2026, 8, 28)));
        assert_eq!(parse_day("2024-02-29"), Some(days_from_civil(2024, 2, 29)));
        assert_eq!(parse_day("2023-02-29"), None);
        assert_eq!(parse_day("2026-13-01"), None);
        assert_eq!(parse_day("2026-8-28"), None);
        assert_eq!(parse_day("+026-08-28"), None);
        assert_eq!(parse_day("2026-08-28T00:00"), None);
    }
}
