# Home users are available to apps

Realm can now give an app the people on the Home it runs on. Two permissions in
`@yourrealm/sdk`, declared in `configSchema` like any other (holden `0dfd6fc3`,
2026-10-30):

- `r.home.users()`: the app gets every member of the Home as
  `[{ id, username, name, role }]`. The operator approves it at install.
- `r.home.user()`: the operator picks one member on the install screen (or later
  in Settings), and the app gets that member as `{ id, username, name, role }`.
  If the picked member leaves the Home, the app stops and its page asks the
  operator to pick again.

`id` and `username` never change; `name` is the editable display name. Email is
never sent. Home re-evaluates the app when someone joins, leaves, changes role
or changes their name. `getTrustedHeaders` can also broker `user.id` now,
alongside `user.username`.

Docs: https://yourrealm.eu/docs/realm-ts/home/users/ and
https://yourrealm.eu/docs/realm-ts/home/user/. Test fixtures take a `users` list
(`realm-sdk test`); `realm-sdk dev` offers the fixture's users in its picker.
