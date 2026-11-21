# Language library

Every text Mate shows or says stands in the language library, never in the code. A text is changed only there,
in every language at once.

| What | Where | Base (English) | German |
|---|---|---|---|
| Screens, messages, head-up display words (`hud_*`) | Android resources | `app/src/main/res/values/strings.xml` | `app/src/main/res/values-de/strings.xml` |
| Voice control: command words and spoken answers (`say_*`) | `app/src/main/resources/net/prototux/aare/speech/` | `befehle-en.json` | `befehle-de.json` |

The language Mate uses follows the device (`Language.code`): German on a German device, English, the base, on any
other. Speech recognition (Whisper) and the place search ask in that language too.

## Listening and speaking per language

`Language.speech(lang)` lists per language the Whisper models offered (the first is set up first) and the first voice;
`Voices.ALL` holds the voices with their language. The phone app offers the device language's models and voices side by
side; with more than one model on the device the user marks the one Mate listens with (`Settings.asrModel`).

| Language | Whisper (download) | Voices (Piper unless noted) |
|---|---|---|
| English | `base.en` English only, the most exact (160 MB); `base` multilingual (160 MB) | Lessac (US, first), Ryan (US), HFC (US), Alba (GB), Northern English (GB) |
| German | `base` (160 MB) | Thorsten (first), Thorsten hoch, Dii hoch, Miro hoch, Eva, Kerstin, Karlsson (Ramona only where already on the device); Kokoro Thorsten and Kerstin (on the device only) |

Mate as the tablet's speech engine for other apps answers in the chosen voice's language.

## Rules

- A new text: an entry with the same name in **every** language file. Names that are not translated (product names)
  get `translatable="false"` and stand in the base file only.
- Placeholders (`%s`, `%1$s`, `%d`) the same in every language.
- Head-up display words (`hud_*`): at most nine characters.
- The voice files: the same commands in the same order and the same `words` keys in every language.

## The check

The unit tests keep the languages level and fail with a list of what is missing:

- `StringsParityTest`: same entries (same count), same placeholders, same number of list items in every
  `values-<language>/strings.xml`; prints the count per language.
- `VoiceCommandsEnTest.everyLanguageHasEveryCommandAndAnswer`: the voice files against the German one.
- `HudTextTest.everyLanguageFits`: the head-up display words fit the line.
- `LanguageSpeechTest`: every language has Whisper models and voices of its own to choose from.

```
./gradlew :app:testDebugUnitTest --tests net.prototux.aare.StringsParityTest
```

## Another language

1. Copy `values/strings.xml` to `values-<language>/strings.xml` (without the `translatable="false"` entries) and
   translate it.
2. Copy `befehle-en.json` to `befehle-<language>.json` and translate the words people say and the answers.
3. Add the language to `Language.SUPPORTED` and `Language.SPEECH` (Whisper models, first voice), its voices to
   `Voices.ALL` with `lang`, and the language to the list in `VoiceCommandsEnTest`.
4. Run the tests.

Not yet in the library: the navigation's spoken directions come from the route server in German and are read with
German patterns (`NaviSpeaker`); in English they need the route server's English and English patterns first.
