package com.habitrain.core.role.behavior;

/**
 * One distinct role-hook method. Entries are ordered and circuit-broken per
 * {@code (role, hookType)}, so two providers hooking the same category for the
 * same role each get their own stable slot and their own breaker.
 *
 * <p>{@link #category()} groups the values belonging to one {@link RoleHooks}
 * category so a registered container can be decomposed into per-type entries.
 */
public enum HookType {

    LIFECYCLE_ON_ASSIGNED(Category.LIFECYCLE),
    LIFECYCLE_ON_LOST(Category.LIFECYCLE),
    LIFECYCLE_ON_GAME_START(Category.LIFECYCLE),
    LIFECYCLE_ON_GAME_END(Category.LIFECYCLE),

    COMBAT_ALLOW_DEATH(Category.COMBAT),
    COMBAT_ON_DEATH(Category.COMBAT),
    COMBAT_ON_KILL(Category.COMBAT),
    COMBAT_ALLOW_DEATH_BY_KILLER(Category.COMBAT),
    COMBAT_ON_ANY_DEATH(Category.COMBAT),
    COMBAT_ON_DEATH_WITH_BODY(Category.COMBAT),

    TICK_ON_SERVER_TICK(Category.TICK),

    INTERACTION_USE_ITEM(Category.INTERACTION),

    SHOP_ALLOW_BUY(Category.SHOP),
    SHOP_ON_BUY(Category.SHOP),
    SHOP_ON_ANY_BUY(Category.SHOP),

    TASK_ON_FINISH_QUEST(Category.TASK),

    MEETING_ON_START(Category.MEETING),
    MEETING_ON_END(Category.MEETING),
    MEETING_ALLOW_VOTE_OUT(Category.MEETING),

    WIN_ALLOW_GAME_END(Category.WIN),
    WIN_EVALUATE_WIN(Category.WIN),
    WIN_AFTER_WINNERS_FINALIZED(Category.WIN);

    /** The {@link RoleHooks} category a hook type belongs to. */
    public enum Category {
        LIFECYCLE, COMBAT, TICK, INTERACTION, SHOP, TASK, MEETING, WIN
    }

    private final Category category;

    HookType(Category category) {
        this.category = category;
    }

    /** The {@link RoleHooks} category this method belongs to. */
    public Category category() {
        return category;
    }
}
