//! Local time per user. Every "today" and "now" in the app goes through
//! here, in the user's IANA time zone, falling back to UTC when unknown.

use crate::date;
use jiff::Timestamp;
use jiff::tz::TimeZone;

pub struct Local {
    /// The local calendar day as days since the epoch (see `date`).
    pub day: i64,
    /// The same day as `YYYY-MM-DD`, the key completions are stored under.
    pub day_key: String,
    /// Minutes since local midnight.
    pub minute: u16,
}

pub fn zone(name: &str) -> TimeZone {
    TimeZone::get(name).unwrap_or(TimeZone::UTC)
}

pub fn is_valid_zone(name: &str) -> bool {
    TimeZone::get(name).is_ok()
}

pub fn local(secs: i64, tz: &TimeZone) -> Local {
    let ts = Timestamp::from_second(secs).unwrap_or(Timestamp::UNIX_EPOCH);
    let z = ts.to_zoned(tz.clone());
    let y = i64::from(z.year());
    let m = u32::try_from(z.month()).unwrap_or(1);
    let d = u32::try_from(z.day()).unwrap_or(1);
    let hour = u16::try_from(z.hour()).unwrap_or(0);
    let minute = u16::try_from(z.minute()).unwrap_or(0);
    Local {
        day: date::days_from_civil(y, m, d),
        day_key: format!("{y:04}-{m:02}-{d:02}"),
        minute: hour * 60 + minute,
    }
}

/// The local calendar day a timestamp fell on, as days since the epoch.
pub fn day_of(secs: i64, tz: &TimeZone) -> i64 {
    local(secs, tz).day
}

#[cfg(test)]
mod tests {
    use super::*;

    // 2026-08-30T22:30:00Z
    const TS: i64 = 1_788_129_000;

    #[test]
    fn local_follows_the_zone() {
        let utc = local(TS, &zone("UTC"));
        assert_eq!(utc.day, date::days_from_civil(2026, 8, 30));
        assert_eq!(utc.day_key, "2026-08-30");
        assert_eq!(utc.minute, 22 * 60 + 30);

        // CEST is UTC+2, so it is already the next day in Stockholm.
        let sto = local(TS, &zone("Europe/Stockholm"));
        assert_eq!(sto.day, date::days_from_civil(2026, 8, 31));
        assert_eq!(sto.day_key, "2026-08-31");
        assert_eq!(sto.minute, 30);
        assert_eq!(day_of(TS, &zone("Europe/Stockholm")), sto.day);
    }

    #[test]
    fn unknown_zones_fall_back_to_utc() {
        assert!(!is_valid_zone("Nowhere/Nope"));
        assert!(is_valid_zone("Europe/Stockholm"));
        assert_eq!(local(TS, &zone("Nowhere/Nope")).day_key, "2026-08-30");
    }
}
