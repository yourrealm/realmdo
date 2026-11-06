//! Calendar days as integers (days since 1970-01-01). Due dates are days, not
//! instants, and day arithmetic is all urgency needs, so no date crate.

use serde::{Deserialize, Serialize};

/// A day of the week, Monday first.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Weekday {
    Mon,
    Tue,
    Wed,
    Thu,
    Fri,
    Sat,
    Sun,
}

impl Weekday {
    pub const COUNT: usize = 7;
    const ALL: [Weekday; 7] = [
        Weekday::Mon,
        Weekday::Tue,
        Weekday::Wed,
        Weekday::Thu,
        Weekday::Fri,
        Weekday::Sat,
        Weekday::Sun,
    ];

    /// The weekday of a day since the epoch (1970-01-01 was a Thursday).
    pub fn of(day: i64) -> Weekday {
        Weekday::ALL[(day + 3).rem_euclid(7) as usize]
    }
}

/// The first day from `from` on (inclusive) that falls on one of `days`.
pub fn next_on(days: &[Weekday], from: i64) -> Option<i64> {
    (from..from + 7).find(|d| days.contains(&Weekday::of(*d)))
}

/// `YYYY-MM-DD` for a day since the epoch (Hinnant's `civil_from_days`).
pub fn day_key(day: i64) -> String {
    let z = day + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

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

    #[test]
    fn day_keys_round_trip() {
        for key in [
            "1970-01-01",
            "1969-12-31",
            "2024-02-29",
            "2026-11-10",
            "2100-03-01",
        ] {
            assert_eq!(day_key(parse_day(key).unwrap()), key);
        }
    }

    #[test]
    fn weekdays_and_the_next_day_on() {
        assert_eq!(Weekday::of(0), Weekday::Thu);
        let tue = days_from_civil(2026, 11, 10);
        assert_eq!(Weekday::of(tue), Weekday::Tue);
        assert_eq!(Weekday::of(tue - 1), Weekday::Mon);
        assert_eq!(Weekday::of(-1), Weekday::Wed);
        let mwf = [Weekday::Mon, Weekday::Wed, Weekday::Fri];
        assert_eq!(next_on(&mwf, tue), Some(tue + 1));
        assert_eq!(next_on(&mwf, tue - 1), Some(tue - 1), "today counts");
        assert_eq!(next_on(&[Weekday::Tue], tue + 1), Some(tue + 7));
        assert_eq!(next_on(&[], tue), None);
    }
}
