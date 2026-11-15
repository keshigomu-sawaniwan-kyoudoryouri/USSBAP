# USSBAP

WASAPI排他出力、DSD Native / DoP、LRCLIB同期歌詞、YouTube検索・ストリーミング・ダウンロードに対応したWindows向け音楽プレーヤーです。

- 配布: `USSBAP.msi`（ユーザー単位・管理者不要）
- インストール先: `%LOCALAPPDATA%\Programs\USSBAP`
- 動作環境: Windows 10 / 11 (64bit)
- プロジェクトページ: https://keshigomu-sawaniwan-kyoudoryouri.github.io/USSBAP/
- リポジトリ: https://github.com/keshigomu-sawaniwan-kyoudoryouri/USSBAP

## 主な機能

- WASAPI排他出力（失敗時は共有モードへフォールバック）
- DSD64/128/256（DSF/DFF）
  - DoP: WASAPI排他（DACのDoP対応が必要）
  - Native: ASIO DSD対応ドライバ経由（未対応時はDoPへフォールバック）
  - DSD再生中はソフトウェア音量を無効化（100%固定）
- LRCLIBの同期歌詞（自動追従・行クリックでシーク）
  - LRCLIB停止/該当なし時はlyrics.ovhのプレーン歌詞へフォールバック
  - ローカルの`.lrc`・埋め込み歌詞に対応
- YouTube検索・ストリーミング（YoutubeExplode + yt-dlpフォールバック、URLキャッシュ・先読み）
- YouTube URLから曲・プレイリスト・アルバム・ミックスを取り込み
- yt-dlpダウンロード（MP3/M4A/FLAC、プレイリストを1アルバムとしてタグ整理）
- ローカル音源のアルバム分類、右クリックで次に再生/キュー追加、シャッフル
- アプリ内アップデート（MSIサイレント適用、設定/キャッシュは保持）

## インストール

1. GitHub Releasesから `USSBAP.msi` をダウンロード
2. 実行してインストール（ユーザー単位のためUACは不要）
3. スタートメニュー / デスクトップのショートカットから起動

## アプリ内アップデート

- 起動時に1日1回、GitHub Pagesの `update.json` を確認します
- 「設定 → アプリ情報 / アップデート」から手動確認もできます
- 更新はMSIをバックグラウンドでダウンロードし、`msiexec /qn` でサイレント適用します
- 設定・歌詞・ストリーム・サムネイル・ツールのキャッシュは引き継がれます
- 更新後は自動でアプリを再起動します

## GitHub Pages

`docs/` を GitHub Pages の公開元にします。

1. リポジトリの Settings → Pages
2. Source: `Deploy from a branch`
3. Branch: `main`、フォルダ: `/docs`
4. `https://keshigomu-sawaniwan-kyoudoryouri.github.io/USSBAP/` が公開されます

`docs/update.json` がアプリの更新マニフェストです。書式:

```json
{
  "version": "1.4.0",
  "url": "https://github.com/keshigomu-sawaniwan-kyoudoryouri/USSBAP/releases/download/v1.4.0/USSBAP.msi",
  "notes": "更新内容",
  "sha256": "MSIのSHA256",
  "published": "2026-11-13T00:00:00Z"
}
```

## リリース手順

1. バージョンを更新（`USSBAP.csproj` / `installer.wxs`）
2. ビルド

```powershell
dotnet publish -c Release -r win-x64 --self-contained true -o publish
Copy-Item publish\USSBAP.exe . -Force
```

3. MSI作成

```powershell
wix build installer.wxs -arch x64 -o USSBAP.msi
```

4. 署名（任意。ローカルの自己署名証明書を使用）

```powershell
$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq "<THUMBPRINT>" }
Set-AuthenticodeSignature .\USSBAP.exe -Certificate $cert -HashAlgorithm SHA256 -TimestampServer "http://timestamp.digicert.com"
Set-AuthenticodeSignature .\USSBAP.msi -Certificate $cert -HashAlgorithm SHA256 -TimestampServer "http://timestamp.digicert.com"
```

5. GitHub Releaseを作成（タグ例: `v1.4.0`）し、`USSBAP.msi` を添付
6. `docs/update.json` を更新

```powershell
# 日本語の更新内容はUTF-8のテキストファイルを渡すと文字化けしません
.\scripts\update-manifest.ps1 -Msi .\USSBAP.msi -Version 1.4.0 -NotesFile .\notes.txt
```

7. `docs/` をpush（Pagesが自動反映）

補足: `docs/update.json` の `url` は `v<version>` タグの `USSBAP.msi` を指します。リリースを先に作成してMSIを添付してから、マニフェストを更新してください。

## ビルド要件

- .NET 9 SDK
- WiX Toolset 5 (`dotnet tool install --global wix`)
- ffmpeg（ストリーミング/変換用。PATHまたは設定から取得）
- yt-dlp（初回起動時に自動取得。設定から更新可能）

## 注意

- DSDのDoPはDAC側のDoP対応が必要です。非対応DACではノイズになるため使用しないでください
- DSD256のDoPは705.6kHz PCMが必要で、DACのPCM上限に依存します
- Native DSDはASIO DSD対応ドライバが必要です
- DST圧縮のDFFは非対応です
- BASS / BASSASIO / BASSDSD を同梱しています。非商用利用は無料、商用利用にはBASSのライセンスが必要です。ライセンスはMSIの `licenses` フォルダに同梱されます
- 署名は自己署名です。他のPCで警告を消すにはCA発行のコード署名証明書が必要です

