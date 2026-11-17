# SDK 依赖

`android.jar`（Android platform-31）和 `r8.jar`（D8）体积较大，不纳入版本库，运行：

```bash
scripts/fetch-deps.sh
```

`shizuku/` 下是 Shizuku 的 classes.jar（api / provider / aidl / shared），遵循 Apache-2.0，
来源 https://github.com/RikkaApps/Shizuku 。
