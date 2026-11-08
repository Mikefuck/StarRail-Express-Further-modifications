package com.habitrain.core.api.role.v2.action;

/** Built-in action outcomes. Custom extension translation keys remain valid. */
public enum RoleActionReason {
    OK(RoleActionResult.OK), UNKNOWN(RoleActionResult.UNKNOWN), WRONG_ROLE(RoleActionResult.WRONG_ROLE),
    WRONG_DIRECTION(RoleActionResult.WRONG_DIRECTION), TOO_LARGE(RoleActionResult.TOO_LARGE),
    RATE(RoleActionResult.RATE), COOLDOWN(RoleActionResult.COOLDOWN), DEAD(RoleActionResult.DEAD),
    HANDLER(RoleActionResult.HANDLER), RANGE(RoleActionResult.RANGE), LINE_OF_SIGHT(RoleActionResult.LINE_OF_SIGHT),
    REPLAY(RoleActionResult.REPLAY), STALE(RoleActionResult.STALE), TARGET(RoleActionResult.TARGET),
    TIMEOUT(RoleActionResult.TIMEOUT), DISCONNECTED(RoleActionResult.DISCONNECTED),
    HANDSHAKE(RoleActionResult.HANDSHAKE), CONFIG_DISABLED(RoleActionResult.CONFIG_DISABLED);

    private final String translationKey;

    RoleActionReason(String translationKey) { this.translationKey = translationKey; }

    public String translationKey() { return translationKey; }

    public static java.util.Optional<RoleActionReason> fromKey(String key) {
        for (var reason : values()) if (reason.translationKey.equals(key)) return java.util.Optional.of(reason);
        return java.util.Optional.empty();
    }
}
