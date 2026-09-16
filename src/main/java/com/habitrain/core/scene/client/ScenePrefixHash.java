package com.habitrain.core.scene.client;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 续传前缀哈希的共享实现：写盘流水线持续喂 digest，续传校验重读前缀比对同一个值。
 *
 * <p>{@link MessageDigest#clone()} 是 public，Sun 的 SHA-256 实现可克隆——克隆出来的实例
 * 可以 {@code digest()} 出"到目前为止已喂入字节"的哈希，而原实例继续推进不受影响。整个断点
 * 续传就建立在这一点上。**克隆不可用时一律降级为"不写续传记录"**，绝不写入假校验值。</p>
 */
final class ScenePrefixHash {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ScenePrefixHash() {}

    /** 新建 SHA-256；当前 JVM 不支持时返回 null（调用方据此禁用续传）。 */
    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** 克隆一个可继续推进的副本；不可克隆时返回 null。 */
    static MessageDigest cloneDigest(MessageDigest digest) {
        if (digest == null) return null;
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException | RuntimeException e) {
            return null;
        }
    }

    /** 取"已喂入字节"的哈希而不影响原实例；不可克隆时返回 null。 */
    static String snapshotHex(MessageDigest digest) {
        MessageDigest snapshot = cloneDigest(digest);
        if (snapshot == null) return null;
        return hex(snapshot.digest());
    }

    static String hex(byte[] bytes) {
        if (bytes == null) return null;
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
