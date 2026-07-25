# 哈比列车核心 — API 参考手册设计

> **日期**: 2026-07-22  
> **状态**: 已批准  
> **产物**: `docs/API参考手册.md`（单文件完整参考）

## 目标

为 `com.habitrain.core.api` 包提供完整参考手册：功能能力表、类/方法级说明、默认值与约束、端到端示例。受众为 DLC 开发者与核心维护者。

## 范围

**纳入**：`api/` 下全部公开类型（16 个）。  
**不纳入**：`task/`、`game/`、`network/` 内部实现作为稳定 API；不重写 `docs/使用教程.md` 正文。

## 结构（方案 A）

1. 概述与命名对照  
2. 功能能力一览  
3. 任务系统  
4. 游戏模式  
5. SRE 集成抽象  
6. 投票 API  
7. 道具回收  
8. 示例  
9. 约束与陷阱  
10. 类索引  

## 验收

- 每个 `api` 类型均有职责 + 方法/字段说明  
- 示例可对照仓库内 `BuiltinTaskRegistrar` / `BlackoutMode` / `CommandRegistrar`  
- 明确 freeze、ARGB、onReclaim 路径、Registry fullId 与 `GameMode.getId()` 差异  
