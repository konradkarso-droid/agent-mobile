# Gate protocol — how agents pass on what was said

[Русская версия](README.md)

For anyone writing their own agent who wants it to talk to agents on this protocol. You do not need our code: everything required is here and in the examples (`primery/`).

The protocol carries **provenance** — who said it, on whose behalf the agent speaks, about whom, when, where — not meaning. Words travel as they were said; what to do with them is up to the receiver and its model. The models at either end can be anything: the only thing agents share is the packet fields, and code (not a model) fills and reads them.

"Must" / "must not" marks what makes another agent refuse a packet. "We" / "ours" is how this agent does it; yours may differ.

If the two versions ever disagree, the Russian one and the code (`app/src/main/java/com/uroboros/gate/`) win.

---

## 1. Channel — Telegram

- **The agent is a Telegram bot.** Packets travel **only in private chats bot ↔ bot**. Both bots must have **Bot-to-Bot Communication** mode enabled in @BotFather (Bot API 10.0 or later). Packets in groups are not accepted: everyone there would see them.
- **A packet is a file** (`sendDocument`), not text. Telegram text is limited to 4096 characters, and an 8 KB packet in Cyrillic does not fit; a file arrives byte for byte. The file name does not matter.
- **Not accepted:**
  - an edit of an already sent message (`edited_message`);
  - a message sent on behalf of a chat (has `sender_chat`);
  - a forwarded packet — its sender is the forwarder while the packet names someone else (see "sender is not the one the channel says" in § 6).
- **Key** of an account — `tg:user:<id>`, where the id is `from.id` from Telegram. The same for people and bots: whether it is a bot is not part of the key. A group is `tg:chat:<id>` (`chat.id`, usually negative). `@username` is **not a key**: it can be changed, and a freed username is taken by someone else.
- **The sender is whoever Telegram says**, not whoever is written in the packet. The receiver checks the key in the packet against the message's `from.id`.

## 2. Frame: header and body

Every form is one header line, a line feed (`\n`, byte 0x0A) and a body:

```
<mark> <version> <body length in bytes> <CRC32 of body> [<signature> [<signature by the previous key>]]\n<body>
```

- **Header** — ASCII, words separated by single spaces, at most 200 bytes including `\n`. Unsigned — four words, signed — five (six for a card when its key changes), § 2a.
- **Mark** — which form this is: `trafaret`, `kartochka`, `poruka`, `prinyal` (§ 4).
- **Version** — an integer. A foreign version is not read at all: its fields may mean something else.
- **Length** — body length **in bytes** (not characters), decimal.
- **CRC32** — the standard CRC-32 of the body (as `zlib.crc32` in Python, `java.util.zip.CRC32`), **8 lowercase** hex digits.
- **Body** — UTF-8, exactly `length` bytes; nothing after it, not even a line feed.
- The checksum catches accidental damage, **not forgery**. The signature guards against forgery (§ 2a).

## 2a. Signature

Every form has two versions: **unsigned** (four header words) and **signed** (five words, version one higher, § 10). The body is the same.

- **Algorithm** — ECDSA on curve P-256 (secp256r1) with SHA-256.
- **Signature** — the fifth header word: r and s, 32 bytes each, back to back (not DER), base64url without `=` — exactly 86 characters.
- **What is signed** — the first four header words joined by spaces, `\n` and the body, byte for byte. The form mark and version are part of what is signed: a signature cannot be moved from one form to another.
- **Public key** — an uncompressed curve point (65 bytes: `04`, x, y), base64url without `=` — 87 characters. It travels in the agent's card (`fields.key`, § 4.2); the card is signed by that same key.
- **Trust on first use.** The first signed card from a channel key sets the agent's key. After that the receiver **refuses** this agent's forms without a signature ("unsigned, but the agent's key is known") and with a signature by another key ("wrong signature"). While the agent's key is unknown, unsigned forms are accepted.
- **Key change.** A new key arrives in a new card. If the same card is also signed by the **previous key** — as the sixth header word, over the same bytes — the key changes without questions. Without the previous key's signature the receiver raises an alarm: the old key stays in force, the new one waits for the receiver's owner.
- **Why.** The channel knows who sent a message only by the bot token. The signing key does not depend on the token: a double with a stolen token cannot sign. With us the private key lives in the Android keystore and never leaves the phone.

## 3. Body: narrow strict JSON

Allowed: object, array, string, **integer**, `true`/`false`, whitespace between them. Forbidden (the whole packet is refused): `null`, fractions and `1e3`, leading zeros, **the same key twice** in an object, nesting deeper than 4 levels, anything after the closing bracket.

Every field is required; **an unknown field is a refusal**. So a packet cannot carry rights, a role or "trust": there is no field for them, and an extra field stops parsing. The words "I am admin" in `words` are just words.

Key order does not matter. We write the body without spaces, keys in the order of the tables below; this equals `json.dumps(obj, ensure_ascii=False, separators=(',', ':'))` in Python.

**Key** — a string `scheme:rest`: the scheme is lowercase Latin letters and digits, the rest has no spaces or control characters, at most 128 characters in total. The only known scheme now is `tg`.

**Moments** — milliseconds since 1970-01-01 UTC, integers greater than zero, by the clock of whoever wrote them.

## 4. Forms

### 4.1. `trafaret 2` / `trafaret 3` — words packet

Someone said something; an agent passes it on to another agent.

| Field | What | Rules |
|---|---|---|
| `from.key` | key of the agent that sent it | must match the sender according to the channel |
| `from.represents` | on whose behalf the agent speaks — a person or a chat; may be itself | a claim; the receiver checks it against its own records |
| `to.key` | who the packet is for | the receiver's key |
| `sent.number` | packet number | 16 lowercase hex digits — a random 64-bit number; a resend of the same packet carries the same number |
| `sent.ms` | moment of the first sending | a resend carries the same moment |
| `place` | where it was said | `DIRECT` (private chat) or `GROUP` |
| `said_by` | who said the words | only `from.key` or `from.represents` — no third hands |
| `about.persons` | about whom — as keys | array of keys; empty with `unclear: false` — about the world |
| `about.unclear` | the sender itself could not tell about whom | `true`/`false` |
| `at.ms` | when it was said | not later than `sent.ms` |
| `at.zone` | sender's time zone | IANA zone name, e.g. `Europe/Moscow` |
| `source` | how the sender knows it | `USER_STATED` — a person said it; `AGENT_INFERRED` — the agent inferred it; `OCR_EXTRACTED` — read from an image |
| `kind` | sentence kind | `STATEMENT`, `QUESTION`, `REQUEST`, `SOCIAL` (greeting, thanks) |
| `topic` | topic — dictionary forms of words | array of strings |
| `exact` | exact values that bypass the model: time, number, link | object "name → string" |
| `words` | the words themselves, as said | non-empty string |

Why: pronouns are not passed on — through two agents "I" and "you" get flipped twice, and models get confused. About whom goes as keys, time as a moment and a zone; "the other day" is made by the receiver. Examples — `primery/paket.txt`, signed — `primery/podpis_paket.txt`.

### 4.2. `kartochka 1` / `kartochka 2` — agent card

What the agent says about itself — the same to everyone.

| Field | What | Rules |
|---|---|---|
| `agent` | the agent's key | must match the sender according to the channel: only the agent itself changes its card |
| `fields` | card fields | object; each field is optional, but if present — a non-empty string |
| `fields.name` | what it calls itself | up to 64 characters |
| `fields.repo` | code repository | up to 200 characters |
| `fields.about` | about itself | up to 280 characters |
| `fields.key` | public signing key (§ 2a) | **only** in a signed card, and required there; 87 characters |

There are no other fields. **No fields about the person** (owner's name, phone) and **no dates** — everyone sees the card. Which is newer is decided by the moment the receiver got it. Examples — `primery/kartochka.txt`, signed — `primery/podpis_kartochka.txt`.

### 4.3. `poruka 1` / `poruka 2` — vouching

"`voucher` vouches for `for` before `to`". Given not to everyone but to whom it was decided to show it: "my owner knows this agent" reveals a circle of acquaintances.

| Field | What | Rules |
|---|---|---|
| `voucher` | who vouches | must match the sender according to the channel |
| `for` | for whom | a key |
| `to` | before whom | the receiver's key |
| `withdraw` | the vouching is withdrawn | `true`/`false` |

With us, vouching switches nothing on by itself: it is a line for the owner; only vouchers the owner knows personally count, and two are needed. Example — `primery/poruka.txt`.

### 4.4. `prinyal 1` / `prinyal 2` — "received"

The receiver's answer to a words packet (only to `trafaret`).

| Field | What | Rules |
|---|---|---|
| `from` | who answers — the packet's receiver | must match the sender according to the channel |
| `to` | to whom — the packet's sender | |
| `number` | the packet number as it arrived | 16 lowercase hex digits |
| `result` | what happened to the packet | `accepted`, `repeat`, `not_for_me`, `stale`, `from_future` |
| `got` | when the packet arrived | by the answerer's clock |
| `sent` | when this answer left | by the same clock; not earlier than `got` |

Example — `primery/prinyal.txt`.

## 5. "Received", resends and clocks

**"Received" means "it arrived", not "I believe it".** Answer even a packet whose words the receiver does not believe: the sender needs to know one thing — whether to send again.

| Outcome of receiving the packet | Answer |
|---|---|
| accepted | `accepted` |
| already accepted (a resend) | `repeat` — the sender may have missed the first answer |
| addressed to someone else | `not_for_me` |
| stale | `stale` |
| from the future | `from_future` — the sender's clock is most likely fast |
| unreadable, sender is not the one the channel says, wrong or missing signature, over the flow limit | **do not answer** |

- **Nobody answers "received".** An answer to an answer is the first loop between two agents.
- Cards and vouching get no "received" — they have no number.
- **Resends.** Without an answer the sender sends the packet again — with the same number and the same `sent.ms`. Ours: after 1 h, then twice as long each time, but at least once a day; 7 days after the first sending — "not delivered". Telegram keeps undelivered updates for a bot no longer than a day, so resends are needed.
- **Clocks of a pair.** The sender remembers when the attempt left (t1) and when the answer arrived (t4); the answer carries `got` (t2) and `sent` (t3). The clock offset is ((t2 − t1) + (t3 − t4)) / 2, as in NTP. We take a measurement only if there was a single attempt and the answer took no longer than 2 minutes; moments in packets are never corrected by it.

## 6. Receiving: checks and refusals

The receiver checks in order and goes silent at the first one that fails. The words in quotes are how the receiver's owner sees them (in our agent they are in Russian).

1. **Size** — ours up to 8 KB. Larger — "larger than the size limit".
2. **Scheme of the channel key** — unknown: "unknown key channel".
3. **Flow limit** — ours: 20 messages of any form per 10 minutes from one channel key. Beyond that — "over the flow limit", no answer. Counted before parsing; garbage counts too. **Do not send one agent more than 20 messages in 10 minutes.**
4. **Header and integrity** — not this form, foreign version, cut off (body shorter than the length), damaged (longer, or the checksum does not match).
5. **Body** — not UTF-8, not narrow JSON, a field missing, an unknown field, a wrong value.
6. **Schemes of the keys in the body.**
7. **Sender** — the key in the packet is not the one the channel says: "sender is not the one the channel says".
8. **Signature** (§ 2a) — the agent's key is known but there is no signature: "unsigned, but the agent's key is known"; a signature by another key: "wrong signature". A card is checked with the key inside it.
9. **Addressee** — not me.
10. **Sending moment** (words packet only) — `sent.ms` older than 7 days by the receiver's clock: "stale"; later than its "now" by more than a day: "from the future".
11. **Repeat** (words packet only) — the pair "sender key + number" was already accepted within the last 7 days.

Passing the gate is not trust yet. Next the receiver decides "who is this": does the agent speak for whoever it named in `from.represents`. With us, "for a person" is only by a power of attorney that the receiver's owner confirmed on their own screen; without it the words go as words of an unfamiliar agent.

## 7. How our agent behaves (recommendations, not rules of the form)

- At most **3 words packets in a row** to one agent without a new message from its own owner; then it goes silent. Telegram's documentation warns plainly: a conversation between bots easily turns into an endless loop.
- It sends its card with the first packet to a new peer and after its card changes.
- Telegram's server time (`date`) is only a witness for its own clock.

## 8. What the protocol does not give

- **No encryption.** Telegram sees the contents of packets.
- **The signature does not catch a takeover before the first meeting**: the first card sets the key. Create the agent's bot from a separate Telegram account and turn on a cloud password there: the owner of the account that created a bot can get its token from @BotFather.
- **Truth is not checked.** The form carries who said it, not whether it is right.
- **Rights are not passed.** No field gives rights in another agent; only that agent's own code does.
- **Meaning between different models is not carried exactly** — only the fields are exact.

## 8a. If your agent runs on iOS or macOS

The protocol itself does not depend on the platform: the frame is text, the signature is P-256, the channel is plain Bot API requests. Our code (Kotlin for Android) will not run on Apple devices — write your own from this description; the examples in `primery/` show whether yours comes out byte for byte the same. Below is where it is easy to go wrong. **None of this has been tried on real Apple devices.**

- **Signing key.** Apple's secure chip (Secure Enclave) stores exactly P-256 keys — the same curve as in § 2a; the private key never leaves the chip, just as ours never leaves the Android keystore. The chip is in iPhones and iPads, in Macs with Apple processors and in Intel Macs with a T2 chip; on a Mac without it the key has to live in the keychain, outside a chip.
  - In CryptoKit a signature has `rawRepresentation` — that is exactly r and s back to back, as required. `SecKeyCreateSignature` from Security returns the signature in DER — split it into r and s (§ 9).
  - The public key is `x963Representation`: the same 65 bytes (`04`, x, y).
  - Sign the `signed` bytes as they are: CryptoKit computes SHA-256 itself. If you hash them first and then sign the hash as data, you get a signature of a hash of a hash, and the receiver answers "wrong signature".
- **Background on iOS.** iOS does not let an app in the background keep polling Telegram (`getUpdates`). There are two ways out:
  - fetch messages while the app is open;
  - a bot webhook to your own server that wakes the device with a push notification. The bot token then also lives on the server — a second place it can be stolen from. A webhook and `getUpdates` do not work together: while a webhook is set, polling gets no updates.
- **Background on macOS.** An app can poll Telegram in the background too, but not while the Mac sleeps (a closed laptop lid means sleep). macOS may also slow down a background app (App Nap) — polling will happen less often.
- **The protocol tolerates delays:** resends go on for up to 7 days (§ 5). But Telegram keeps undelivered updates for a bot no longer than a day — fetch at least once a day, or packets are lost until the next resend.
- **"Received" will arrive later.** If the answer took longer than 2 minutes, no clock measurement is taken (§ 5) — there will be few measurements; that is not a fault.
- **Do not send the backlog all at once.** On waking, do not send one agent everything that piled up: more than 20 messages in 10 minutes is "over the flow limit", with no answer (§ 6).
- **A compressed words channel** is not in the protocol yet. When it comes, it will require the small model to compute identically at both ends; on Apple devices the model usually runs on the GPU (Metal), and matching an Android phone's CPU will have to be checked separately.

## 9. Examples and checking

`primery/` holds one file per form, written by this agent's code; the `GateProtocolExamplesTest` test reads them back. **The files have no line feed at the end**, and one must not be added: the body would get longer than declared and the packet would be "damaged". (GitHub's web editor may add one on its own when editing.)

Check the frame of your packet in Python:

```python
import json, zlib

raw = open("paket.txt", "rb").read()
head, body = raw.split(b"\n", 1)
words = head.decode("ascii").split(" ")
magic, version, length, crc = words[:4]
assert len(body) == int(length), "cut off or extra bytes after the body"
assert format(zlib.crc32(body), "08x") == crc, "checksum mismatch"
print(magic, version, json.loads(body))
```

Check the signature (the `cryptography` library):

```python
import base64
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils

def unb64(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

signed = " ".join(words[:4]).encode("ascii") + b"\n" + body
key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), unb64(key_b64))  # key_b64 — fields.key from the card
raw_sig = unb64(words[4])
der = utils.encode_dss_signature(int.from_bytes(raw_sig[:32], "big"), int.from_bytes(raw_sig[32:], "big"))
key.verify(der, signed, ec.ECDSA(hashes.SHA256()))  # mismatch — InvalidSignature
```

To sign: sign `signed` with your key; `utils.decode_dss_signature` splits the signature into r and s; each is 32 bytes, back to back, in base64url without `=`.

Build the body the same way we do: `json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")`.

## 10. Versions

| Mark | Version | What |
|---|---|---|
| `trafaret` | 2 | words packet, unsigned; since version 2 — number and sending moment (`sent`) |
| `trafaret` | 3 | the same, signed |
| `kartochka` | 1 | agent card, unsigned |
| `kartochka` | 2 | the same, signed and with a key (`fields.key`) |
| `poruka` | 1 | vouching, unsigned |
| `poruka` | 2 | the same, signed |
| `prinyal` | 1 | "received" answer, unsigned |
| `prinyal` | 2 | the same, signed |

When the meaning or the set of fields changes, the version goes up. An agent that does not know the version does not read the packet and refuses it as "foreign version".

This agent's code: `app/src/main/java/com/uroboros/gate/` — `GateWire` (frame and forms), `GateIntake` (receiving), `GateSign` (signature), `GateKeys` (whose key), `GateAck`, `GateOutbox`, `GateClock`, `GateTelegram`.
