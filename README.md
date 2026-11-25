# Vox Ferrum

Vox Ferrum is an audio-only instant replay app for Android 10 and later. It keeps a rolling buffer of sound from your apps, your microphone, or both. When something worth keeping happens, save the last 15 seconds to 15 minutes as a 48 kHz, 16-bit stereo WAV.

[Download the APK](https://github.com/udhd12/VoxFerrum/releases/latest)

## Getting started

The app interface is in Russian. On the **Replay** tab (Повтор), tap **Source** (Источник) and choose what to record.

| Source in the app | What goes into the clip |
| --- | --- |
| Звук приложений | Audio from games and players that allow playback capture |
| Микрофон | Your voice and sounds around the phone |
| Приложения + микрофон | App audio and microphone input mixed into one WAV. Use headphones to keep the microphone from picking up the speakers a second time. |

1. Open Vox Ferrum before starting your game. Choose a replay length of 15 or 30 seconds, or 1, 2, 5, 10, or 15 minutes. Export gain ranges from 0 to +18 dB. At 0 dB, the saved samples keep their original levels.
2. Tap **Enable replay** (Включить повтор) and grant microphone access. To capture app audio, also accept the Android screen-sharing prompt. Vox Ferrum uses it to capture audio only. Microphone-only mode needs just the microphone permission.
3. To keep a moment, expand the Vox Ferrum notification and tap **Save** (Сохранить). Capture continues while the clip is saved. If the buffer has not filled yet, the clip contains the audio collected so far.
4. Open **Archive** (Архив) to listen or share. You can also find the WAV files in `Music/VoxFerrum` on your phone. To switch sources, stop capture first. Stopping clears the temporary buffer.
5. Under **Settings → Appearance** (Настройки → Оформление), choose **Forge of the Omnissiah** or **Tide**.

Short replays use RAM. Longer replays use temporary segments in the app’s private storage. Allow up to 175 MB for a 15-minute buffer and another 173 MB to save a full-length WAV. If you increase the replay length during a game, the extra history builds up from that point. The counter shows how much audio is available.

Games and players can block playback capture. Their audio will be silent in the clip. Call recording is unsupported. [Android playback capture restrictions](https://developer.android.com/media/platform/av-capture)

## Background recording

Once replay is running, you can switch apps or swipe Vox Ferrum away from Recents. Use the notification to save a clip or stop recording.

On Android 15 QPR1 and later, locking the screen can end the system’s playback capture session. In mixed mode, the microphone continues recording into the same buffer. After unlocking, open **Replay → Restore app audio** (Вернуть звук приложений) and approve the new Android prompt. App audio resumes from that moment. Powering off the phone or force-stopping Vox Ferrum ends recording. [Android media projection behavior](https://developer.android.com/media/grow/media-projection)

## If the microphone is silent

1. Choose a source that includes the microphone, start replay, and say a few words. The **Microphone signal** meter (Сигнал микрофона) shows the input level before mixing. The active input and sample rate appear beside it.
2. If a headset is connected, stop capture and try **Input → Built-in phone microphone** (Вход → Встроенный микрофон телефона). Android makes the final routing decision.
3. Check the app’s microphone permission, Android’s **Microphone access** switch, and any other apps recording your voice. Vox Ferrum shows a specific warning when Android reports that it has silenced the input.
4. If the microphone cuts out when a game opens, stop replay, select a microphone source, and enable **Recording priority** (Приоритет записи) before restarting. On Android 11 and later, this asks Android to treat the recording as privacy-sensitive. The game’s voice chat may then lose microphone input. The setting is off by default. Calls retain priority, and system access restrictions still apply. [How Android shares audio input](https://developer.android.com/media/platform/sharing-audio-input)

## After a reboot or power-off

Open Vox Ferrum at least once after installation and allow notifications. **Settings → Start after power-on** is enabled by default. After the first unlock, tap the **Enable audio replay?** notification. Microphone mode starts a fresh buffer if permission has already been granted. App audio and mixed mode require fresh approval in the Android capture dialog.

If notifications are disabled, open Vox Ferrum from the launcher after boot and follow the prompt for your selected mode. If you dismiss the notification, you can still start replay manually. Recording begins when the new session starts. [Android capture service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)

## Quick Settings, widget, and markers

In Vox Ferrum settings, tap **Add Quick Settings tile** or **Add widget**. If your launcher does not support adding a widget from the app, select Vox Ferrum from the launcher’s widget picker.

The start control uses your selected source and opens the system consent dialog when needed. During recording, tap the tile to save a replay. The widget also lets you add a marker, stop capture, or open the archive. On phones with vibration support, a short vibration confirms a successful save.

Tap **Mark** (Метка) in the app, widget, or notification to mark the current moment. Each saved clip includes the markers that fall within its time range. Their positions are stored in the app. After 12 seconds of near-silence, Vox Ferrum displays a hint. In the Forge theme, the red lens indicates active capture, the brass arc shows how much of the replay window is full, and a wax seal briefly appears when a clip is saved.

## Trimming and managing clips

Open **Archive → Trim** (Архив → Обрезать). Drag a selection boundary on the waveform or use the sliders. The **−** and **+** buttons move the boundary by 0.1 seconds. Selecting a marker picks up to five seconds on either side of it.

Tap **Listen to selection**, then **Send selection…** and choose the Telegram bot or Android’s share menu. A trimmed selection is saved as a separate WAV with the markers inside it. Selecting the entire recording uses the original file.

Tap the gear on a clip to add it to your favorites. **Favorites** filters the archive. **Clean up…** lets you review clips older than 7, 30, or 90 days and shows their count and size before asking for confirmation. Cleanup preserves favorites.

## Sharing to Telegram

**To send a clip from your Telegram account**, tap **↗** beside the clip in the archive, choose Telegram, and select the chat with the bot. This works without a bot token.

**To have your own bot deliver clips to a chat or channel:**

1. Create a bot with [@BotFather](https://t.me/BotFather). For a private chat, open the bot and tap **Start**. For a channel, grant the bot permission to post.
2. Under **Settings → Configure bot**, enter the token and either the numeric chat ID or the channel’s public `@username`. The app encrypts the token and destination on the phone using a key from Android Keystore. Tap **Disconnect** in the same dialog to disconnect the bot.
3. In the archive, tap **Telegram**, check the destination, and confirm. The bot sends WAV files up to 50 MB. For larger files, the app creates an AAC-encoded M4A copy for sending and keeps the original WAV in the archive. [Telegram Bot API file limits](https://core.telegram.org/bots/api#senddocument)

## Building from source

Open the project in Android Studio with JDK 17 and Android SDK Platform 36 installed. Set `ANDROID_HOME` or specify `sdk.dir` in `local.properties`. From the project root on Windows, run:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Development used the Codex `agent-skills` plugin and ImageGen for the Forge artwork. The build uses Android Gradle Plugin 8.13.2 (`com.android.application`). The installed app runs without plugins.
