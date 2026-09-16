package com.habitrain.core.scene.client;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 部分下载数据文件的写盘抽象。
 *
 * <p>存在的唯一理由是让单测能注入"写失败/短写"（磁盘满、句柄被占用），
 * 这是覆盖"不推进 committed、按同一偏移重写"这条路径的前提。</p>
 *
 * <p><b>偏移是显式的</b>：不依赖"流位置恰好等于已确认字节数"这种隐式约定。
 * 因此 {@code [0, committed)} 之外的内容永远只是"待覆盖的残留"，不是数据。</p>
 */
public interface ScenePartFile {
    /** 从 {@code offset} 起写入 {@code length} 字节；必须写满，否则抛 {@link IOException}。 */
    void writeAt(long offset, byte[] data, int length) throws IOException;

    long size() throws IOException;

    /** 截断到指定长度。只允许在**首次发起请求之前**调用（新建与续传校验两处）。 */
    void truncate(long length) throws IOException;

    /** 关闭句柄。Windows 上必须先关闭才能移动或删除对应文件。 */
    void close() throws IOException;

    @FunctionalInterface
    interface Factory {
        ScenePartFile open(Path target) throws IOException;
    }
}
