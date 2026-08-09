# necesse-harness

Python client and pytest plugin for
[Necesse Headless Harness](https://github.com/EliasVahlberg/necesse-headless-harness).

It drives a **real Necesse dedicated server** — placing objects, filling containers, opening UIs,
clicking inventory slots — and returns values you can assert on:

```python
def test_a_chest_holds_what_you_put_in_it(harness):
    harness.place("storagebox", 2, 0)
    harness.fill(2, 0, "ironbar", 40)
    assert harness.item_at(2, 0, "ironbar") == 40
```

Requires a licensed Necesse install and the harness mod installed in the game's mods folder. It
cannot run on hosted CI, and it is versioned with the jar it talks to — both are released from the
same repository, and the client refuses a protocol version it does not recognise.

Alpha. The API will change.
