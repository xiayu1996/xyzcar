# Git Commit 规范

[Conventional Commits 1.0](https://www.conventionalcommits.org/en/v1.0.0/)。

```
<type>(<scope>)!: <subject>

<body>

<footer>
```

- `type`：`feat` `fix` `refactor` `perf` `docs` `test` `build` `ci` `chore` `style` `revert`
- `scope`：模块，小写 kebab-case，如 `auth` `player` `discover` `recommend` `subs` `search` `me` `theme` `api` `release`
- `subject`：祈使句，首字母小写，无句号，header ≤ 72 字符
- `body`：可选，72 列换行，写原因

MUST：
1. 全英文，无 emoji。
2. 一个提交一件事。

NEVER：
1. 非标准 type（`feature` `update` `optimize`）。
2. 无信息 scope（`dev` `app` `xyzcar` `code` `misc`）。
3. 含糊 subject（`fix bug` `update` `wip`）。
4. 改写已推送历史。
5. 用 `--no-verify` / `-n`、`-c core.hooksPath=...`、`GIT_CONFIG_*` 绕过 hook。

## 执行

- `.githooks/commit-msg` 校验提交信息。
- 本仓库 `git config core.hooksPath .githooks`（clone 后执行一次）。
- `.claude/settings.json` 给 agent 注入同样配置；`.claude/hooks/git-commit-guard.sh` 拦截绕过参数。
