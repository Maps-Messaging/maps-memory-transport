# Contributing

Development work is performed on the `development` branch or on feature branches created from `development`.

Release flow:

1. Merge tested release content from `development` into `main`.
2. Create release branches from `main`.
3. Rebuild/reset `development` from the released `main` baseline and continue development.

Please keep transport code independent of MapsMessaging server protocol, session, topic, selector, and message-layer concerns.
