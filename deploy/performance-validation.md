# WGL / WWC / WWW — リリース前性能・実機回帰検証

**状態: 計測ツール準備中。本番VPS計測および実機合否は未確認。**

正本: Google Drive `OriginalPlugins/共通基盤/WWC・WWW_必要時更新・相互軽量化計画.md`。
対象バージョン: WGL 1.0.1 / WWC 2.0.1 / WWW 1.0.1。

## 安全条件

- スクリプトは Linux `/proc` の読み取りのみ。明示的な `--rcon` がある場合だけ、既存RCONへ `spark tps` を送る。サーバー停止・再起動・JAR置換は実施しない。
- 実機の新旧比較は、バックアップ済みの隔離検証環境で行う。本番でプレイヤーやワールドを勝手に変更しない。
- CPU全スレッドとMSPTの区別を維持する。旧JARと新JARの各SHA-256、導入日時、設定、ロード済み個体とプレイヤー数、測定時間帯を記録する。
- Sparkプロファイラが既に動作中の場合は競合させない。profileの保存形式・権限を確認してから実行する。

## 読み取り専用の準備確認

```bash
cd /opt/minecraft/server
systemctl is-active minecraft.service
ps -eo pid,ppid,args | grep '[j]ava'
for jar in plugins/WonderfulGenomeLib*.jar plugins/WhatAWonderfulChicken*.jar plugins/WhatAWonderfulWolf*.jar; do
  [ -f "$jar" ] || continue
  sha256sum "$jar"
  unzip -p "$jar" plugin.yml | grep -E '^(name|version):'
done
```

JavaのLauncherとPaper子プロセスは別PIDになる可能性がある。PaperのPIDを選ぶ。

## 診断実行

`--pid` を実際のPaper JVM PIDに、`--out` を新規のGit管理外ディレクトリに変更する。

```bash
python3 deploy/capture-performance.py \
  --pid 12345 --scenario idle --duration 180 --interval 10 \
  --plugins-dir plugins \
  --out /root/minecraft-performance/idle-20261017 --rcon
```

`--rcon`は既存の `RCON_PASSWORD`（必要なら`RCON_HOST`、`RCON_PORT`）が設定され、sparkが使用可能な場合だけ付ける。認証情報は保存しない。

出力:
- `metadata.json`: シナリオ、PID/start-time、JAR SHA-256、plugin.yml version。
- `samples.jsonl`: CPU秒、CPUコア換算、RSS、スレッド数。オプションで `spark tps` の応答。
- `summary.json`: サンプル別CPUコア換算の中央値・p95/p99（**MSPT p95/p99ではない**）。

Sparkのmain-thread/async-thread stackを調べるには管理者がSparkの稼働を確認して、手動で `spark profiler start --thread *`、180秒後 `spark profiler stop --save-to-file` を実行する。プラグインが既にprofiling中なら開始せず既存データを使用する。公式構文: https://spark.lucko.me/docs/Command-Usage

### 対照シナリオ

1. idle（ロード個体数とonlineプレイヤー数を固定）。
2. 50/200/1000個体（**検証環境のみ**。active比率0%/代表比率/100%を分離）。
3. 通常プレイ、JE/BE、ClickMobs後入れPDC、複数World、トラップ、Founder生成、繁殖。
4. 旧JARと新JARを**同じ条件で**別実行。CPU core rateだけでTPS改善とは断定せず、SparkのTPS・MSPT p95/p99、GC、同期/非同期スレッド別sampleを採集する。

## 回帰チェック（各結果にJAR SHAと実測証拠を紐付ける）

| 領域 | 必須ケース |
| --- | --- |
| 起動/Genome | WGL service、WWC/WWW登録、自然・特殊Founder、繁殖、Marker、リログ |
| ClickMobs | 後入れPDC、別Entity差替え、アイテム化/復元/再起動 |
| WWC | FOLLOW/WAIT/TRAP、経路再生成、Display、頭装備、騎乗/スタミナ、特性、年齢 |
| WWW | FOLLOW/GUARD/WAIT、攻撃・武器、Aura、Watchman、Scavenger、GUI、好感度 |
| 時刻/ワールド | 睡眠、time飛越/巻き戻し、各ワールドのトラップ期限、チャンク unload |
| クライアント/保存 | Geyser/BE操作、Genome外部record、旧PDC互換、原子的save、欠損時エラー |

ステータスは **未実施／成功／失敗／検証不能** を分ける。

## リリース判定

自動CI成功だけでは実機合格としない。機能互換性とMSPT p95/p99、CPU処理総量を確認し、劣化または未測定ならmainマージを保留する。mainは本番デプロイ状態と一致させる。