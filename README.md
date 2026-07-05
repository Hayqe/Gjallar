<p align="center">
  <img alt="Gjallar" title="Gjallar" src="mockup/svg/tempus-horizontal-banner.png" width="250">
</p>

---

<p align="center">
  <b>Access your music library on all your android devices — including Sonos speakers</b>
</p>

<div align="center">
  <a href="https://www.gnu.org/licenses/gpl-3.0">
    <img src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
  </a>
</div>

**Gjallar** is a fork of [Tempus](https://github.com/eddyizm/tempus) (originally [Tempo](https://github.com/CappielloAntonio/tempo)), adding first-class **Sonos speaker support**. It remains an open-source, privacy-focused Subsonic music client for Android, with a clean interface built around your listening history.

---

## Features

### Music playback
- **Subsonic Integration** — connect to any Subsonic-compatible server (Navidrome, Airsonic, Gonic, etc.)
- **Browse and Search** — artists, albums, genres, playlists, decades, and more
- **Streaming and Offline Mode** — stream directly or cache for offline listening
- **Playlist Management** — create, edit, and manage playlists
- **Gapless Playback**, **ReplayGain**, **Transcoding Support**
- **Podcasts & Internet Radio** — listen to podcasts and save radio stations locally
- **Instant Mix** — leverage Subsonic's similarSongs endpoints for auto-generated queues
- **Equalizer** — built-in or third-party
- **Widget** — basic playback controls on your home screen
- **Multi-language** — 12 languages

### 🎵 Sonos integration *(this fork)*
- **Device discovery** via mDNS — automatically finds Sonos speakers on your network
- **Stream directly to Sonos** — select a speaker and play your Subsonic library through it
- **Volume control** — adjust Sonos group volume from the app
- **"Playing on SONOS" badge** — replaces the seek bar when a Sonos device is active
- **Robust error handling** — graceful recovery from network issues and session errors

---

## Building

```bash
git clone git@github.com:Hayqe/Gjallar.git
cd Gjallar
./gradlew :app:assembleTempusDebug
```

APK output: `app/build/outputs/apk/tempus/debug/`

---

## License

Gjallar is released under the [GNU General Public License v3.0](LICENSE).

## Credits

- Original [Tempo](https://github.com/CappielloAntonio/tempo) by [CappielloAntonio](https://github.com/CappielloAntonio)
- [Tempus](https://github.com/eddyizm/tempus) fork by [eddyizm](https://github.com/eddyizm)
- Sonos integration built on the [aiosonos](https://github.com/bdraco/aiosonos) Python library's API patterns
