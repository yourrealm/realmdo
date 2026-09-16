//! The four daily slots and a user's start times for them.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Slot {
    Morning,
    BeforeLunch,
    Afternoon,
    Evening,
}

impl Slot {
    pub const ALL: [Slot; 4] = [
        Slot::Morning,
        Slot::BeforeLunch,
        Slot::Afternoon,
        Slot::Evening,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Slot::Morning => "morning",
            Slot::BeforeLunch => "before_lunch",
            Slot::Afternoon => "afternoon",
            Slot::Evening => "evening",
        }
    }

    pub fn parse(s: &str) -> Option<Slot> {
        Slot::ALL.into_iter().find(|slot| slot.as_str() == s)
    }
}

const MINUTES_PER_DAY: u16 = 24 * 60;

/// Minutes since local midnight at which each slot begins. A slot ends where
/// the next begins; evening ends at midnight.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct SlotTimes {
    pub morning: u16,
    pub before_lunch: u16,
    pub afternoon: u16,
    pub evening: u16,
}

impl SlotTimes {
    /// What a fresh user gets. The truth is the column defaults in db.rs's
    /// v4 migration; this mirrors them for tests.
    #[cfg(test)]
    pub const DEFAULT: SlotTimes = SlotTimes {
        morning: 7 * 60,
        before_lunch: 11 * 60,
        afternoon: 14 * 60,
        evening: 19 * 60,
    };

    pub fn start(&self, slot: Slot) -> u16 {
        match slot {
            Slot::Morning => self.morning,
            Slot::BeforeLunch => self.before_lunch,
            Slot::Afternoon => self.afternoon,
            Slot::Evening => self.evening,
        }
    }

    pub fn end(&self, slot: Slot) -> u16 {
        match slot {
            Slot::Morning => self.before_lunch,
            Slot::BeforeLunch => self.afternoon,
            Slot::Afternoon => self.evening,
            Slot::Evening => MINUTES_PER_DAY,
        }
    }

    pub fn is_ordered(&self) -> bool {
        self.morning < self.before_lunch
            && self.before_lunch < self.afternoon
            && self.afternoon < self.evening
            && self.evening < MINUTES_PER_DAY
    }
}

/// `HH:MM` to minutes since midnight.
pub fn parse_hhmm(s: &str) -> Option<u16> {
    let (h, m) = s.split_once(':')?;
    if h.len() != 2 || m.len() != 2 {
        return None;
    }
    let h: u16 = h.parse().ok()?;
    let m: u16 = m.parse().ok()?;
    if h > 23 || m > 59 {
        return None;
    }
    Some(h * 60 + m)
}

pub fn format_hhmm(minutes: u16) -> String {
    format!("{:02}:{:02}", minutes / 60, minutes % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slots_round_trip_their_names() {
        for slot in Slot::ALL {
            assert_eq!(Slot::parse(slot.as_str()), Some(slot));
        }
        assert_eq!(Slot::parse("lunch"), None);
        assert_eq!(
            serde_json::to_string(&Slot::BeforeLunch).unwrap(),
            "\"before_lunch\""
        );
    }

    #[test]
    fn times_parse_format_and_order() {
        assert_eq!(parse_hhmm("07:00"), Some(420));
        assert_eq!(parse_hhmm("23:59"), Some(1439));
        assert_eq!(parse_hhmm("24:00"), None);
        assert_eq!(parse_hhmm("7:00"), None);
        assert_eq!(parse_hhmm("07:60"), None);
        assert_eq!(format_hhmm(1140), "19:00");
        assert!(SlotTimes::DEFAULT.is_ordered());
        assert_eq!(SlotTimes::DEFAULT.end(Slot::Morning), 660);
        assert_eq!(SlotTimes::DEFAULT.end(Slot::Evening), 1440);
        let bad = SlotTimes {
            morning: 700,
            ..SlotTimes::DEFAULT
        };
        assert!(!bad.is_ordered());
    }
}
