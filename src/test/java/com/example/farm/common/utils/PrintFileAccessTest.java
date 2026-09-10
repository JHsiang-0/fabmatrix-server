package com.example.farm.common.utils;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintFile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class PrintFileAccessTest {

    @Test
    void rejectsMissingAncestorAndNonFolderParent() {
        PrintFile file = file(3L, 2L, 99L, "PRIVATE", false);
        Map<Long, PrintFile> records = new HashMap<>();
        records.put(file.getId(), file);

        assertThatThrownBy(() -> PrintFileAccess.requireAccessible(3L, records::get, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件不存在");

        PrintFile parent = file(99L, 2L, null, "PRIVATE", false);
        records.put(parent.getId(), parent);
        assertThatThrownBy(() -> PrintFileAccess.requireAccessible(3L, records::get, 2L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件不存在");
    }

    @Test
    void rejectsCyclesVisibilityConflictsAndPrivateOwnerMismatch() {
        PrintFile first = file(1L, 1L, 2L, "PRIVATE", true);
        PrintFile second = file(2L, 1L, 1L, "PRIVATE", true);
        Map<Long, PrintFile> cycle = Map.of(1L, first, 2L, second);
        assertThatThrownBy(() -> PrintFileAccess.requireAccessible(1L, cycle::get, 1L, false))
                .isInstanceOf(BusinessException.class);

        PrintFile root = file(10L, 1L, null, "PUBLIC", true);
        PrintFile conflicting = file(11L, 1L, 10L, "PRIVATE", false);
        Map<Long, PrintFile> conflict = Map.of(10L, root, 11L, conflicting);
        assertThatThrownBy(() -> PrintFileAccess.requireAccessible(11L, conflict::get, 2L, false))
                .isInstanceOf(BusinessException.class);

        PrintFile privateRoot = file(20L, 1L, null, "PRIVATE", true);
        PrintFile privateChild = file(21L, 2L, 20L, "PRIVATE", false);
        Map<Long, PrintFile> ownerMismatch = Map.of(20L, privateRoot, 21L, privateChild);
        assertThatThrownBy(() -> PrintFileAccess.requireAccessible(21L, ownerMismatch::get, 1L, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void allowsPublicFileOnlyWhenItsCompletePathIsValid() {
        PrintFile root = file(30L, 1L, null, "PUBLIC", true);
        PrintFile child = file(31L, 2L, 30L, "PUBLIC", false);
        Map<Long, PrintFile> records = Map.of(30L, root, 31L, child);

        assertThat(PrintFileAccess.requireAccessible(31L, records::get, 99L, false))
                .isSameAs(child);
    }

    private PrintFile file(Long id, Long userId, Long parentId, String visibility, boolean folder) {
        PrintFile file = new PrintFile();
        file.setId(id);
        file.setUserId(userId);
        file.setParentId(parentId);
        file.setVisibility(visibility);
        file.setIsFolder(folder);
        return file;
    }
}
