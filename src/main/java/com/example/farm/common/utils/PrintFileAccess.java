package com.example.farm.common.utils;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintFile;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared file-resource authorization. A file is readable only when its complete
 * parent chain is structurally valid and has one consistent visibility value.
 */
public final class PrintFileAccess {

    private static final String PRIVATE = "PRIVATE";
    private static final String PUBLIC = "PUBLIC";
    private static final int MAX_FOLDER_LEVEL = 2;

    private PrintFileAccess() {
    }

    public static PrintFile requireAccessible(Long fileId, Function<Long, PrintFile> lookup,
                                              Long currentUserId, boolean admin) {
        if (fileId == null || lookup == null) {
            throw notFound();
        }
        PrintFile leaf = lookup.apply(fileId);
        if (leaf == null || leaf.getId() == null) {
            throw notFound();
        }

        Set<Long> visited = new HashSet<>();
        List<PrintFile> pathNodes = new ArrayList<>();
        PrintFile current = leaf;
        PrintFile root = null;
        String visibility = null;
        int folderLevel = Boolean.TRUE.equals(leaf.getIsFolder()) ? 1 : 0;
        while (current != null) {
            if (current.getId() == null || !visited.add(current.getId())) {
                throw notFound();
            }
            pathNodes.add(current);
            String currentVisibility = normalizeVisibility(current.getVisibility());
            if (currentVisibility == null
                    || (visibility != null && !visibility.equals(currentVisibility))
                    || current.getUserId() == null) {
                throw notFound();
            }
            visibility = currentVisibility;

            Long parentId = current.getParentId();
            if (parentId == null) {
                root = current;
                break;
            }
            PrintFile parent = lookup.apply(parentId);
            if (parent == null || !Boolean.TRUE.equals(parent.getIsFolder())) {
                throw notFound();
            }
            folderLevel++;
            if (folderLevel > MAX_FOLDER_LEVEL) {
                throw notFound();
            }
            current = parent;
        }

        if (root == null || (leaf.getParentId() != null && !Boolean.TRUE.equals(root.getIsFolder()))) {
            throw notFound();
        }
        if (!admin) {
            if (currentUserId == null || PRIVATE.equals(visibility)
                    && !Objects.equals(root.getUserId(), currentUserId)) {
                throw notFound();
            }
            if (PRIVATE.equals(visibility)) {
                for (PrintFile node : pathNodes) {
                    if (!Objects.equals(node.getUserId(), root.getUserId())) {
                        throw notFound();
                    }
                }
            }
        }
        return leaf;
    }

    public static PrintFile requireAccessible(Long fileId, Function<Long, PrintFile> lookup) {
        return requireAccessible(fileId, lookup, SecurityContextUtil.getCurrentUserId(),
                SecurityContextUtil.isAdmin());
    }

    private static String normalizeVisibility(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return PUBLIC.equals(normalized) || PRIVATE.equals(normalized) ? normalized : null;
    }

    private static BusinessException notFound() {
        return new BusinessException(404, "文件不存在");
    }
}
