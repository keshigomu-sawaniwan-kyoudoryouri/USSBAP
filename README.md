# USSBAP — USB Streaming BitPerfect Audio Player

Android 向けの音楽プレーヤーです。ローカル音源、YouTube / YouTube Music のストリーミング、USB DAC への出力に対応します。USB DAC の出力には、Android の USB Audio HAL を利用する方法と、USB Audio Class の再生インターフェースへ PCM を直接送る方法があります。

> **音量に関する注意**  
> DIRECT USB は Android の通常のシステム音量を通りません。DAC 側の音量を低くしてから再生してください。再生設定では「DAC側」（アプリ側で音量を変更しない）と「アプリ側」（ソフトウェア音量）を選べます。アプリ側のソフトウェア音量は安全のため DAC 最大出力の約 10% を上限とし、初期値はスライダー 10%（DAC 最大出力の約 1%）です。実際の音量は DAC、音源、接続機器にも依存するため、最初は低音量で確認してください。

## 主な機能

- USB DAC の検出、USB 接続許可、DAC が公開する Android Mixer Attributes の表示
- 3 種類の出力モード
  - **DIRECT USB** — USB Audio Class の再生用 alternate setting を選び、PCM を USB DAC へ直接送信します。対応するサンプルレート／形式を確認できない場合は直接出力を開始しません。
  - **USB AUDIO HAL API** — Android USB Audio HAL と `AudioManager` の Mixer Attributes API を利用します。ビットパーフェクト属性の有無・利用可否は端末と DAC によります。
  - **OFF / SYSTEM OUTPUT** — 通常の Android 音声出力を使用します。
- DIRECT USB の音量方式切替：DAC 側（ソフトウェアゲインなし）／アプリ側（PCM にソフトウェアゲイン）
- USB 接続中のローカル音楽ライブラリ、フォルダー選択、プレイリスト、再生履歴、キュー
- YouTube / YouTube Music 検索・ストリーミング、ログイン Cookie の取得または手動設定、ダウンロード管理
- 圧縮ストリーミング音源向け DAU 音声補完とストリーミング DSP / EQ
- DSF / DFF / DSDIFF の読み込みと PCM 変換再生
- USB DAC の Mixer Attributes を確認する独立した `dac-probe` 診断アプリ

## USB 出力と音量

出力モードと音量方式は、アプリの **再生設定 → USB OUTPUT ROUTING** で選択します。

### DIRECT USB

- USB デバイスの再生用 AudioStreaming インターフェース、alternate setting、サンプルレートとサンプル形式を確認して USB ストリームを構成します。
- ローカル FLAC はネイティブ FLAC デコード経路を利用できる場合があります。その他の対応 PCM は Media3 のデコード後に USB へ送信します。
- この経路では Android のシステム音量は適用されません。
- **DAC側**を選ぶとソフトウェア音量を適用せず、アプリはサンプル値を音量調整しません。DAC の物理音量で調整してください。USB Audio Class のハードウェア音量制御をアプリから操作するモードではありません。
- **アプリ側**を選ぶと PCM にソフトウェアゲインを適用します。スライダー 100% がゲイン 0.1（DAC 最大出力の約 10%）、初期値のスライダー 10% がゲイン 0.01（約 1%）です。音量変更によりビットパーフェクトではなくなります。
- アプリ画面が表示されているとき、DIRECT USB とアプリ側音量の組み合わせでは端末の音量キーがアプリ音量スライダーを調整します。DAC側モードでは端末の音量キーで直接出力の音量は変わりません。
- USB 許可、対応する再生 alternate setting、形式、エンドポイント容量、USB ストリームの初期化などが満たせない場合、raw USB 再生を有効として扱わず、通常の出力経路へ戻します。

### USB AUDIO HAL API

Android が DAC に対して公開する Mixer Attributes を参照し、条件が合えば希望する属性を設定します。属性の公開や `BIT_PERFECT` のサポートは Android バージョンだけでは決まらず、端末メーカーの USB Audio HAL と DAC の組み合わせに依存します。

### DSD

`.dsf`、`.dff`、`.dsdiff` はネイティブ DSD / DoP ではなく、FFmpeg で **176.4 kHz PCM に変換して**再生します。

## ビットパーフェクトについて

出力モード名や API の成功だけでは、DAC までの信号がビットパーフェクトであることを保証できません。Mixer Attributes の公開状況、再生経路、デコード・変換・DSP・音量処理などに左右されます。特に次の処理は音声サンプルを変更するため、元音源のビットパーフェクト再生ではありません。

- アプリ側ソフトウェア音量
- DAU、DSP / EQ などの音声処理
- DSD から PCM への変換
- 出力形式やチャンネル数などの変換

厳密な確認には DAC のサンプルレート表示や、適切な測定機器による検証が必要です。アプリの表示、USB へのルーティング、`setPreferredMixerAttributes` の成功だけを厳密な証明として扱わないでください。

## 必要環境

- Android 14（API 34）以降の端末。アプリの `minSdk` は 34、`targetSdk` は 35。
- USB ホスト機能と、利用する USB DAC に対応する USB Audio Class の再生インターフェース。
- ビルド環境：JDK 17、Android SDK 35、Android NDK 26.1.10909125、CMake 3.22.1 以降。
- アプリ本体の NDK ABI は `arm64-v8a`。

## ビルド

リポジトリのルートで実行します。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

アプリは USB ドライバーと Media3 ラッパーを `app/libs/` の Release AAR として参照します。これらのライブラリをソースから変更してアプリに取り込む場合は、先にライブラリをビルドし、AAR をコピーしてからアプリをビルドしてください。

```bash
./gradlew :decent-usb-audio-driver:testReleaseUnitTest
./gradlew :decent-usb-audio-driver:assembleRelease
./gradlew :decent-usb-audio-wrapper-media3:assembleRelease
cp decent-libs/decent-usb-audio-driver/build/outputs/aar/decent-usb-audio-driver-release.aar app/libs/
cp decent-libs/decent-usb-audio-wrapper-media3/build/outputs/aar/decent-usb-audio-wrapper-media3-release.aar app/libs/
./gradlew :app:assembleRelease
```

USB Mixer Attributes の検査用アプリは別モジュールです。

```bash
./gradlew :dac-probe:assembleDebug
```

## プロジェクト構成

- `app/` — プレーヤー UI、ライブラリ、ストリーミング、再生サービス、出力モード・音量設定
- `dac-probe/` — USB DAC の Android Mixer Attributes を調べる診断アプリ
- `decent-libs/decent-usb-audio-driver/` — USB デバイス管理、UAC 記述子解析、ネイティブ USB PCM 出力、FLAC エンジン
- `decent-libs/decent-usb-audio-wrapper-media3/` — Media3 `AudioSink` と DIRECT USB の統合
- `decent-libs/decent-media3-decoder-flac/` — FLAC デコーダーおよび関連するネイティブソース

## 使用ライブラリとライセンス

USSBAP 本体は **GNU General Public License v3.0 (GPL-3.0)** で公開します。ソースコードは [GitHub](https://github.com/keshigomu-sawaniwan-kyoudoryouri/USSBAP) にあります。ライセンス全文は `LICENSE`、第三者ソフトウェアの一覧と著作権表示は `THIRD_PARTY_NOTICES.md`、各ライセンス全文は `licenses/` にあります。アプリ内では **設定 → ライセンス / NOTICE** からも確認できます。

プロジェクトには AndroidX / Media3、NewPipe Extractor、OkHttp、FFmpegKit Maintained、yt-dlp Android、ONNX Runtime、Chaquopy、Decent USB Audio、libFLAC などを使用しています。各ライブラリのライセンスと配布条件に従ってください。`decent-libs/LICENSE-decent-player.txt` は同梱されている DecentPlayer 部分のライセンスです。

APK やソース一式を配布する前に、`app/src/main/res/values/strings.xml` の `app_source_url`、`THIRD_PARTY_NOTICES.md`、`NOTICE` に書かれた GitHub のプレースホルダーURLを実際のリポジトリURLへ置き換えてください。
