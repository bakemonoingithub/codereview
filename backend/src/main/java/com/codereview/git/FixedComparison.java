package com.codereview.git;

import com.codereview.common.BusinessException;
import com.codereview.common.ResultCode;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 两个固定提交之间的净变化。PR 审查使用共同祖先到源提交的树比较，不读取第一父提交。
 *
 * <p>文件类别依据 Git 模式和对象内容，不用扩展名，也不把「没有 patch」当成二进制。
 * 超过调用方给出的文件数上限时只返回数量，不返回部分路径，避免把截断清单当成完整范围。
 */
public final class FixedComparison {

    public static final int MAX_FILES = 3000;
    public static final String COMPLETE = "COMPLETE";
    public static final String TRUNCATED = "TRUNCATED";
    public static final String TEXT = "TEXT";
    public static final String BINARY = "BINARY";
    public static final String UNREADABLE = "UNREADABLE";
    public static final String COPIED = "copied";

    private static final byte[] LFS_PREFIX =
            "version https://git-lfs.github.com/spec/v1".getBytes(StandardCharsets.US_ASCII);

    private FixedComparison() {
    }

    public record FileChange(String path, String oldPath, String status, String category, String reason) {

        public boolean text() {
            return TEXT.equals(category);
        }
    }

    public record Result(String targetHead, String sourceHead, String mergeBase, String completeness,
                         int fileCount, List<FileChange> files) {

        public Result {
            files = files == null ? List.of() : List.copyOf(files);
        }

        public boolean complete() {
            return COMPLETE.equals(completeness);
        }
    }

    /** 先算 {@code targetHead} 与 {@code sourceHead} 的共同祖先，再比较共同祖先到源提交。 */
    public static Result between(File gitDir, String targetHead, String sourceHead,
                                 int maxFiles, int maxBytes) throws IOException {
        try (Repository repo = open(gitDir)) {
            RevCommit target = commit(repo, targetHead);
            RevCommit source = commit(repo, sourceHead);
            RevCommit mergeBase = mergeBase(repo, target, source);
            if (mergeBase == null) {
                throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                        "两个提交没有共同祖先，不能计算 PR 净变化");
            }
            return scan(repo, target.getName(), source.getName(), mergeBase, source, maxFiles, maxBytes);
        }
    }

    /** 比较已经冻结的共同祖先与源提交，不再重新寻找祖先。 */
    public static Result trees(File gitDir, String mergeBase, String sourceHead,
                               int maxFiles, int maxBytes) throws IOException {
        try (Repository repo = open(gitDir)) {
            RevCommit base = commit(repo, mergeBase);
            RevCommit source = commit(repo, sourceHead);
            return scan(repo, null, source.getName(), base, source, maxFiles, maxBytes);
        }
    }

    private static Result scan(Repository repo, String targetHead, String sourceHead, RevCommit base,
                               RevCommit source, int maxFiles, int maxBytes) throws IOException {
        int limit = Math.max(1, maxFiles);
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            formatter.setDetectRenames(true);
            List<DiffEntry> entries = formatter.scan(base.getTree(), source.getTree());
            if (entries.size() > limit) {
                return new Result(targetHead, sourceHead, base.getName(), TRUNCATED, entries.size(), List.of());
            }
            List<FileChange> files = new ArrayList<>();
            for (DiffEntry entry : entries) {
                FileChange change = toChange(repo, entry, maxBytes);
                if (change != null) {
                    files.add(change);
                }
            }
            files.sort(Comparator.comparing(FileChange::path));
            return new Result(targetHead, sourceHead, base.getName(), COMPLETE, files.size(), files);
        }
    }

    private static FileChange toChange(Repository repo, DiffEntry entry, int maxBytes) throws IOException {
        String oldPath = path(entry.getOldPath());
        String newPath = path(entry.getNewPath());
        String status = switch (entry.getChangeType()) {
            case ADD -> ChangedFile.ADDED;
            case DELETE -> ChangedFile.REMOVED;
            case RENAME -> ChangedFile.RENAMED;
            case COPY -> COPIED;
            default -> ChangedFile.MODIFIED;
        };
        String path = ChangedFile.REMOVED.equals(status) ? oldPath : newPath;
        if (path == null) {
            return null;
        }
        String recordedOld = ChangedFile.ADDED.equals(status) ? null : oldPath;
        if (recordedOld != null && recordedOld.equals(path) && !ChangedFile.REMOVED.equals(status)) {
            recordedOld = null;
        }
        Classification category = classify(repo, entry, status, maxBytes);
        return new FileChange(path, recordedOld, status, category.category, category.reason);
    }

    private static Classification classify(Repository repo, DiffEntry entry, String status, int maxBytes)
            throws IOException {
        boolean before = !ChangedFile.ADDED.equals(status);
        boolean after = !ChangedFile.REMOVED.equals(status);
        Classification left = before
                ? classifyBlob(repo, entry.getOldId().toObjectId(), entry.getOldMode(), maxBytes) : null;
        Classification right = after
                ? classifyBlob(repo, entry.getNewId().toObjectId(), entry.getNewMode(), maxBytes) : null;
        if (left != null && BINARY.equals(left.category) || right != null && BINARY.equals(right.category)) {
            return new Classification(BINARY, BINARY);
        }
        if (left != null && !TEXT.equals(left.category)) {
            return left;
        }
        if (right != null && !TEXT.equals(right.category)) {
            return right;
        }
        return new Classification(TEXT, null);
    }

    private static Classification classifyBlob(Repository repo, ObjectId id, FileMode mode, int maxBytes)
            throws IOException {
        if (mode == FileMode.GITLINK) {
            return new Classification(UNREADABLE, "SUBMODULE");
        }
        if (mode == FileMode.SYMLINK) {
            return new Classification(UNREADABLE, "SYMLINK");
        }
        if (id == null || ObjectId.zeroId().equals(id)) {
            return new Classification(UNREADABLE, "MISSING");
        }
        try (ObjectReader reader = repo.newObjectReader()) {
            ObjectLoader loader = reader.open(id);
            long size = loader.getSize();
            int probeLength = (int) Math.min(size, 8192);
            byte[] probe = readPrefix(loader, probeLength);
            if (hasNul(probe)) {
                return new Classification(BINARY, BINARY);
            }
            if (size > maxBytes) {
                return new Classification(UNREADABLE, "TOO_LARGE");
            }
            if (startsWith(probe, LFS_PREFIX)) {
                return new Classification(UNREADABLE, "LFS_POINTER");
            }
            byte[] all = size <= probeLength ? probe : loader.getBytes();
            if (!isUtf8(all)) {
                return new Classification(UNREADABLE, "ENCODING");
            }
            return new Classification(TEXT, null);
        } catch (IOException e) {
            return new Classification(UNREADABLE, "MISSING");
        }
    }

    private static byte[] readPrefix(ObjectLoader loader, int length) throws IOException {
        byte[] probe = new byte[length];
        try (InputStream input = loader.openStream()) {
            int offset = 0;
            while (offset < length) {
                int read = input.read(probe, offset, length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return probe;
    }

    private static boolean hasNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Repository open(File gitDir) throws IOException {
        return new FileRepositoryBuilder().setGitDir(gitDir).build();
    }

    private static RevCommit commit(Repository repo, String sha) throws IOException {
        ObjectId id = resolve(repo, sha);
        if (id == null) {
            throw new BusinessException(ResultCode.GIT_COMPARE_FAILED.getCode(),
                    "本地镜像里找不到引用 " + sha);
        }
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.parseCommit(id);
        }
    }

    /** 无共同祖先时返回 null。这里不能退回某一侧提交，否则会把无关联历史当成净变化。 */
    private static RevCommit mergeBase(Repository repo, RevCommit target, RevCommit source) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(target.getId()));
            walk.markStart(walk.parseCommit(source.getId()));
            return walk.next();
        }
    }

    private static ObjectId resolve(Repository repo, String sha) {
        try {
            ObjectId id = repo.resolve(sha);
            return id != null && repo.getObjectDatabase().has(id) ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String path(String value) {
        if (value == null || value.isBlank() || DiffEntry.DEV_NULL.equals(value)) {
            return null;
        }
        return value;
    }

    private record Classification(String category, String reason) {
    }
}
