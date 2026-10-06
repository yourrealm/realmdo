//! Urgency: one number per task, computed on read, never stored. The main
//! list sorts by it, descending; below 0 a task is hidden; at 1 and above it
//! is "important". These constants are a sketch to settle against real use.

use crate::slots::{Slot, SlotTimes};
use serde::Serialize;

/// Days before its due date at which a dated one-off enters the list, unless
/// the task sets its own.
pub const ONCE_LEAD_DAYS: i64 = 7;
/// Where an undated one-off sits: fixed, in the middle.
const ONCE_UNDATED: f64 = 0.5;

/// One-off task: hidden until `lead` days before the due day, then rising
/// from 0 to reach 1 on the due day, and on past it. A lead of 0 shows up at
/// 1 on the day itself.
pub fn once(due_day: Option<i64>, lead: Option<i64>, today: i64) -> f64 {
    let Some(due) = due_day else {
        return ONCE_UNDATED;
    };
    let lead = lead.unwrap_or(ONCE_LEAD_DAYS).max(0);
    let rate = lead.max(1) as f64;
    if due - today > lead {
        // Hidden; the general formula would put a lead of 0 at 0 the day before.
        (lead - (due - today)) as f64 / rate
    } else {
        1.0 + (today - due) as f64 / rate
    }
}

/// Interval task, measured from the last completion (or creation): hidden
/// for `after` days, then rising to 1 over `ramp` days and on past it.
pub fn interval(after: i64, ramp: Option<i64>, last_day: i64, today: i64) -> f64 {
    let ramp = ramp.unwrap_or_else(|| default_ramp(after)).max(1);
    ((today - last_day) - after) as f64 / ramp as f64
}

/// "Every X days" without a ramp: important once a whole interval overdue.
pub fn default_ramp(after: i64) -> i64 {
    after.max(1)
}

/// Where one of today's slots stands at a given minute of the local day.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum SlotState {
    Done,
    Upcoming,
    Current,
    Overdue,
}

pub fn slot_state(slot: Slot, times: &SlotTimes, minute: u16, checked: bool) -> SlotState {
    if checked {
        SlotState::Done
    } else if minute < times.start(slot) {
        SlotState::Upcoming
    } else if minute < times.end(slot) {
        SlotState::Current
    } else {
        SlotState::Overdue
    }
}

const SLOT_OVERDUE: f64 = 1.5;
const SLOT_CURRENT: f64 = 1.0;
/// Hidden from ranked lists.
const SLOT_UPCOMING: f64 = -0.5;
/// Everything checked (or nothing left today): hidden.
const SLOT_DONE: f64 = -1.0;

/// Slot task: the most pressing of today's slots decides. A passed unchecked
/// slot outranks the current one, and both are important.
pub fn slots(states: impl IntoIterator<Item = SlotState>) -> f64 {
    states
        .into_iter()
        .map(|s| match s {
            SlotState::Overdue => SLOT_OVERDUE,
            SlotState::Current => SLOT_CURRENT,
            SlotState::Upcoming => SLOT_UPCOMING,
            SlotState::Done => SLOT_DONE,
        })
        .fold(SLOT_DONE, f64::max)
}

pub fn is_important(urgency: f64) -> bool {
    urgency >= 1.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn once_rises_to_one_on_the_due_day() {
        let today = 20_000;
        assert_eq!(once(None, None, today), 0.5);
        assert_eq!(once(Some(today), None, today), 1.0);
        assert_eq!(once(Some(today + 7), None, today), 0.0);
        assert!(
            once(Some(today + 8), None, today) < 0.0,
            "far out is hidden"
        );
        assert_eq!(once(Some(today - 7), None, today), 2.0);
        assert!(!is_important(once(Some(today + 1), None, today)));
        assert!(is_important(once(Some(today), None, today)));
    }

    #[test]
    fn once_lead_sets_when_it_shows_up() {
        let today = 20_000;
        // Two days ahead: hidden at 3, appears at 2, halfway at 1.
        assert!(once(Some(today + 3), Some(2), today) < 0.0);
        assert_eq!(once(Some(today + 2), Some(2), today), 0.0);
        assert_eq!(once(Some(today + 1), Some(2), today), 0.5);
        assert_eq!(once(Some(today), Some(2), today), 1.0);
        // On the day only: hidden the day before, important on the day.
        assert!(once(Some(today + 1), Some(0), today) < 0.0);
        assert_eq!(once(Some(today), Some(0), today), 1.0);
        assert_eq!(once(Some(today - 1), Some(0), today), 2.0);
        // A lead does nothing without a date.
        assert_eq!(once(None, Some(0), today), 0.5);
    }

    #[test]
    fn interval_follows_the_spec_example() {
        // Clean bathroom: after 6, ramp 5. Hidden days 0-5, appears on day 6,
        // important on day 11, keeps rising.
        let done = 20_000;
        let u = |day: i64| interval(6, Some(5), done, done + day);
        for day in 0..6 {
            assert!(u(day) < 0.0, "day {day} should be hidden");
        }
        assert_eq!(u(6), 0.0);
        assert_eq!(u(8), 0.4);
        assert_eq!(u(11), 1.0);
        assert!(is_important(u(11)));
        assert_eq!(u(16), 2.0);
    }

    #[test]
    fn interval_defaults_and_edges() {
        let done = 20_000;
        // "Workout every 3 days": ramp defaults to after, so important on day 6.
        assert_eq!(interval(3, None, done, done + 3), 0.0);
        assert_eq!(interval(3, None, done, done + 6), 1.0);
        // after 0 is visible again right away; ramp never divides by zero.
        assert_eq!(interval(0, None, done, done), 0.0);
        assert_eq!(interval(0, Some(0), done, done + 1), 1.0);
        assert_eq!(default_ramp(0), 1);
    }

    #[test]
    fn slot_states_follow_the_clock() {
        let t = SlotTimes::DEFAULT;
        let at = |hhmm: u16, slot| slot_state(slot, &t, hhmm, false);
        assert_eq!(at(6 * 60, Slot::Morning), SlotState::Upcoming);
        assert_eq!(at(7 * 60, Slot::Morning), SlotState::Current);
        assert_eq!(at(10 * 60 + 59, Slot::Morning), SlotState::Current);
        assert_eq!(at(11 * 60, Slot::Morning), SlotState::Overdue);
        assert_eq!(at(11 * 60, Slot::BeforeLunch), SlotState::Current);
        assert_eq!(at(23 * 60 + 59, Slot::Evening), SlotState::Current);
        assert_eq!(at(12 * 60, Slot::Evening), SlotState::Upcoming);
        assert_eq!(
            slot_state(Slot::Morning, &t, 12 * 60, true),
            SlotState::Done
        );
    }

    #[test]
    fn slot_urgency_takes_the_most_pressing() {
        use SlotState::*;
        assert_eq!(slots([]), -1.0);
        assert_eq!(slots([Done, Done]), -1.0);
        assert_eq!(slots([Done, Upcoming]), -0.5);
        assert_eq!(slots([Upcoming, Current]), 1.0);
        assert_eq!(slots([Overdue, Current, Upcoming]), 1.5);
        assert!(is_important(slots([Current])));
        assert!(!is_important(slots([Upcoming])));
    }
}
