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

`main` には DiscordSRV 関連などの `develop` の変更が未反映です。起動前にブランチを必ず確認してください。ローカルに未コミットの変更がある場合は、それを確認してから切り替え・更新します。

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

BlueMap の内部 Web サーバーは `127.0.0.1:8100` で待ち受けます。公開ドメインを使わずに確認する場合は、次の SSH ポート転送を VSCode の**ローカル**ターミナルで実行し、`http://localhost:8100` を開きます（接続パラメーターは自分の環境の値に置換）。

```powershell
ssh -i "$HOME/.ssh/鍵ファイル名" -L 8100:127.0.0.1:8100 SSHユーザー名@Minecraft_VPSのIP
```

## VPS へ接続する（VSCode のターミナルから）

VSCode のローカルターミナルで、実際の SSH ユーザー・秘密鍵・Minecraft 側 VPS の IP を使用します。SSH 接続に Minecraft の playit.gg アドレスは使用しません。

```powershell
ssh -i "$HOME/.ssh/鍵ファイル名" SSHユーザー名@Minecraft_VPSのIP
```

以降のコマンドは SSH 接続先の **Ubuntu** で実行します。サーバーの配置パスは VPS 上の実際のパスに読み替えてください。既存サーバーの実行ユーザーとファイル所有者を確認し、Minecraft を root として新規起動しないでください。

```bash
cd /実際の配置先/MineCraftXPlayServer
pwd
git branch --show-current
git status --short
java -version
javac -version
ps -eo pid,user,args | grep '[p]aper.jar'
tmux ls
```

`ps` にサーバーが表示される場合は **二重起動しません**。既存の tmux セッションや、実際に採用しているプロセス管理方法を確認します。`tmux ls` にセッションがない場合でも、`ps` で実行中なら新規起動しないでください。

## VPS での起動・停止・更新

以下は tmux を使用してコンソールを保持する場合の操作例です。`tmux` がない環境では先に導入する必要があります。すでに別の方法で常駐起動している場合は、その方式を優先し、この手順と混在させないでください。

停止済みであること、`.env`・`server.properties`・ワールドデータなど VPS 固有の実データが揃っていることを確認した上で、必要ならブランチを更新します。

```bash
git fetch origin --prune
git switch develop
git pull --ff-only origin develop
[ -f .env ] || cp .env.example .env
javac Launcher.java
tmux new -s xplay
```

開いた tmux セッション内で次を実行します。

```bash
java Launcher
```

コンソールから離れるだけなら `Ctrl+B` の後に `D` でデタッチします。別の SSH セッションから再接続する場合は以下を実行します。

```bash
tmux attach -t xplay
```

停止は **接続した Paper コンソールへ `stop` を入力**します。正常終了後にシェルへ戻ったら、必要に応じて `exit` で tmux セッションを終了します。設定・コード・プラグイン更新時は、サーバーを停止し、ワールド等のバックアップを取得してから `git pull --ff-only origin develop` と再起動を行います。起動中にプラグインやワールドのファイルを Git 操作で上書きしないでください。

ログの確認先はコンソールおよび `logs/latest.log` です。

```bash
tail -n 100 logs/latest.log
tail -f logs/latest.log
```

`Launcher.java` のコンパイルでできる `Launcher.class` は Git の追跡対象ではありません。`git status --short` で変更内容を確認し、実行時データや Secret をコミットしないでください。

## 管理対象とバックアップ

- Git 管理: `Launcher.java`、`jvm.args`、`paper.jar`、`plugins/` 内の追跡対象、`datapacks/` など。
- Git 管理外: `world/` などのワールド、`server.properties`、`.env`、`logs/`、`bluemap/`、プレイヤーデータ・権限ファイル等。別途バックアップ・復元が必要です。
- `jvm.args` のメモリ指定は VPS の空きメモリも考慮し、起動前に確認してください。

構成の参照先: [サーバー構成](https://drive.google.com/file/d/1SndNSbyQX5HUEEE-ueQAofPO0bZ6jvto/view)、[接続関係](https://drive.google.com/file/d/1I1ZpsEXeJMAhAhrEcpgu3yhNP9FDhDzL/view)。
