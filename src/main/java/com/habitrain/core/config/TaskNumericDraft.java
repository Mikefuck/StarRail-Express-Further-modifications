package com.habitrain.core.config;

/** Parse every numeric field before changing values or override flags. Rewards may be signed. */
public record TaskNumericDraft(boolean hasGold, int gold, boolean hasEmotion, float emotion,
                               boolean hasWeight, float weight) {
    public static TaskNumericDraft parse(String gold, String emotion, String weight) {
        gold = gold.trim(); emotion = emotion.trim(); weight = weight.trim();
        int g = gold.isEmpty() ? 0 : ConfigValidation.integer(gold, "金币奖励");
        float e = emotion.isEmpty() ? 0 : ConfigValidation.finiteFloat(emotion, "情绪奖励");
        float w = weight.isEmpty() ? 0 : ConfigValidation.finiteFloat(weight, "刷新权重");
        if (w < 0) throw new IllegalArgumentException("刷新权重不能为负数；0 表示禁用");
        return new TaskNumericDraft(!gold.isEmpty(), g, !emotion.isEmpty(), e, !weight.isEmpty(), w);
    }
    public void apply(TaskConfigEntry entry) {
        entry.hasGoldReward = hasGold; entry.goldReward = gold;
        entry.hasEmotionReward = hasEmotion; entry.emotionReward = emotion;
        entry.hasRefreshWeight = hasWeight; entry.refreshWeight = weight;
    }
    public void apply(MinigameConfigEntry entry) {
        entry.hasGoldReward = hasGold; entry.goldReward = gold;
        entry.hasEmotionReward = hasEmotion; entry.emotionReward = emotion;
        entry.hasRefreshWeight = hasWeight; entry.refreshWeight = weight;
    }
}
