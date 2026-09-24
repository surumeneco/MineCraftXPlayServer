# MineCraftXPlayServer

Paper Minecraft サーバーの構成と起動用 `Launcher.java` を管理するリポジトリです。DiscordSRV はこの Minecraft サーバー上のプラグインとして動作し、独自 DiscordBot は別リポジトリ・別 VPS で動作します。

## VSCode で開いた直後

VSCode で本リポジトリのフォルダーを開き、**[ターミナル] → [新しいターミナル]** を選択します。以下のローカル手順は Windows 11 の PowerShell、VPS 内の手順は Ubuntu のシェルを想定しています。コマンドは特記がなければリポジトリのルートで実行します。

作業対象は `develop` です。まずブランチを更新します。

```powershell
git fetch origin --prune
git switch develop
git pull --ff-only origin develop
git status --short
```

`main` / `develop` の役割は作業時点のGitHub上の状態を確認してください。起動前にブランチを必ず確認し、ローカルに未コミットの変更がある場合は、それを確認してから切り替え・更新します。

## ローカルで起動する

前提は `java` と `javac` が使える、現在の `paper.jar` に対応する JDK です。バージョンと配置を確認します。

```powershell
java -version
javac -version
Test-Path .\paper.jar
Test-Path .\jvm.args
```

初回のみ、`.env` がなければ `.env.example` をコピーします。DiscordSRV を Discord に接続する場合は、共有する Bot のトークンを `.env` の `DISCORD_BOT_TOKEN` に設定します。DiscordSRV を使わない検証では空欄のままでも Launcher 自体は起動を続行しますが、Discord には接続できません。

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

`.env` は Git 管理対象外です。Token を `plugins/DiscordSRV/config.yml` や README に直接記入しないでください。Launcher が `.env` の `DISCORD_BOT_TOKEN` を Paper 子プロセスの `DISCORDSRV_TOKEN` に引き渡します。先にプロセス環境変数 `DISCORDSRV_TOKEN` が設定されている場合はそちらが優先されます。

**起動する前に**、`server.properties` とワールドデータを確認します。両者は Git には含まれません。既存ワールドを使用する場合、別途バックアップから復元してください。未配置で起動すると新規ワールドが生成され得ます。

```powershell
javac Launcher.java
java Launcher
```

コンパイルエラーが出た場合は起動コマンドを実行しません。Launcher は `datapacks/` を `world/datapacks/` に同期しますが、**同期先の既存ファイルをすべて消してからコピー**します。`world/datapacks/` に直接置いた独自ファイルは消えるので、管理するデータパックは `datapacks/` に配置してください。また、起動前に ClickMobs の生成済み `en_US.json` を削除します。

サーバーコンソールが表示されたら起動ログを確認します。停止するときはサーバーコンソールへ `stop` を入力して Enter を押し、正常終了を確認します。VSCode のターミナルを閉じるだけでは正常停止の代わりになりません。

## Minecraft と BlueMap への接続

| 対象 | 接続先 | 備考 |
| --- | --- | --- |
| Java Edition | `xplayje.mc.surumene.co` | Minecraft の「サーバーを追加」でアドレスを入力 |
| Bedrock Edition | `xplaybe.mc.surumene.co`、ポート `12504` | Bedrock の外部サーバー追加画面でアドレスとポートを別々に入力 |
| BlueMap | `https://xplayweb.surumene.co` | Web ブラウザーからアクセス。公開トンネルの稼働が必要 |

Java/Bedrock の公開には playit.gg、BlueMap の公開には Cloudflare Tunnel を使用します。Minecraft 本体の起動と、各トンネルの起動・接続状態は別々に確認してください。Bedrock の外部ポート `12504` と、Geyser の VPS 内 UDP ポート `19132` は別の値です。

BlueMap の内部 Web サーバーは環境別設定の待受IP・ポートを使用します。公開ドメインを使わず確認する場合は、BlueMap の実際の待受IPとVPSのSSH経路に合わせてポート転送を設定します。

## VPS へ接続する（VSCode のターミナルから）

VSCode のローカルターミナルで、実際の SSH ユーザー・秘密鍵・Minecraft 側 VPS の IP を使用します。SSH 接続に Minecraft の playit.gg アドレスは使用しません。

```powershell
ssh -i "$HOME/.ssh/鍵ファイル名" SSHユーザー名@Minecraft_VPSのIP
```

以降のコマンドは SSH 接続先の **Ubuntu** で実行します。現在の配置先は `/opt/minecraft/server`、常駐起動は `minecraft.service` による systemd 管理です。**tmux や `java Launcher` で二重起動しません。**

```bash
cd /opt/minecraft/server
pwd
git branch --show-current
git status --short
java -version
javac -version
systemctl status minecraft.service --no-pager
```

## VPS での起動・停止・更新

2026-09-23 の運用変更方針として、VPS の Minecraft と Paper を root で起動します。手順の正本は **[deploy/root-migration.md](deploy/root-migration.md)**、ユニットのテンプレートは **[deploy/minecraft.service](deploy/minecraft.service)** です。VPS固有の `/etc/systemd/system/minecraft.service`、ワールド、`.env`、ファイル所有者はpushだけでは変更されません。

この構成では Paper とすべてのプラグインが root の権限を持つため、任意コード実行やプラグイン侵害時に OS 全体へ影響し得ます。また root 化は過去の `AccessDeniedException` の原因解明・解消を保証しません。

停止済みであること、VPS 固有のデータが揃っていることを確認してから更新します。**停止・バックアップ → `/opt/minecraft/server` と `.git` の所有者を root に変更 → `develop` の更新 → systemd のユニット反映 → 起動・検証**の順番は移行マニュアルを参照してください。移行後の日常操作は以下です。

```bash
sudo systemctl stop minecraft.service
cd /opt/minecraft/server
sudo git pull --ff-only origin develop
sudo systemctl start minecraft.service
sudo systemctl status minecraft.service --no-pager -l
```

`Launcher.java` を編集した場合は、起動前にJDKの状況を確認してください。現行の `ExecStart=/usr/bin/java Launcher.java` はソースファイルをJavaで直接実行します。ログは `sudo journalctl -u minecraft.service -f` および `logs/latest.log` で確認します。Git管理外データやSecretをコミットしないでください。

## 日次再起動

本番Minecraftサーバーは毎日03:00（Asia/Tokyo）前後に自動再起動する構成です。02:55に `minecraft-maintenance.timer` が処理を開始し、RCONで5分前通知、10秒前からのカウントダウン、`save-all flush` を実行してから `minecraft.service` を再起動します。

RCONのSecretと `server.properties` はGit管理外です。導入・更新・確認手順は **[deploy/daily-restart.md](deploy/daily-restart.md)** を参照してください。

## 管理対象とバックアップ

- Git 管理: `Launcher.java`、`jvm.args`、`paper.jar`、`plugins/` 内の追跡対象、`datapacks/`、`deploy/` など。
- Git 管理外: `world/` などのワールド、`server.properties`、`.env`、`logs/`、`bluemap/`、プレイヤーデータ・権限ファイル等。別途バックアップ・復元が必要です。
- `jvm.args` のメモリ指定は VPS の空きメモリも考慮し、起動前に確認してください。
- root 運用に変更後は、Git操作もrootで実行し、元の `minecraft` ユーザーによるGit操作・ファイル生成と混用しません。

構成の参照先: [サーバー構成](https://drive.google.com/file/d/1ufmtWfd-PDFU5Ac8709cApoY2vMogRZ8/view)、[接続関係](https://drive.google.com/file/d/1I1ZpsEXeJMAhAhrEcpgu3yhNP9FDhDzL/view)。
