# CODEBUDDY.md

本项目的开发约定、构建方式、硬性约束与目录说明，**统一维护在 [`AGENTS.md`](./AGENTS.md)**，
不在此重复，以免多份内容不同步。

**开工顺序**：

1. 读 [`AGENTS.md`](./AGENTS.md) —— 工程入口：唯一工作区、构建命令、硬性约束、git 规矩。
2. 读 [`PROGRESS.md`](./PROGRESS.md) —— 当前进度：做到哪、下一步是什么、卡在哪。
3. 需要纵深时再读 [`交接说明.md`](./交接说明.md)（总纲）与 [`docs/01-项目架构与代码地图.md`](./docs/01-项目架构与代码地图.md)。

**收工前**：更新 [`PROGRESS.md`](./PROGRESS.md)。

---

## 四条最容易出事的红线（完整版见 `AGENTS.md` §3）

- 唯一工作区 `C:\starword`，**不要创建第二份副本**。
- 日常只构建 debug；**绝不自动跑 `assembleRelease`**，除非开发者明确说「跑 release」。
- `testdata/`（私拍原图 / `.gray`）**绝不提交、打包、分发**。
- 提交身份用 `1437nb <1437nb@users.noreply.github.com>`；**不要改写 git 历史**
  （本环境 `rebase` 有删除 `.git` 的前科）。
