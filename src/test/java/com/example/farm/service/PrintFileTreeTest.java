package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.config.FileUploadProperties;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.vo.FileNodeVO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.impl.PrintFileServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintFileTreeTest {

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private RustFsClient rustFsClient;

    @Mock
    private FileUploadProperties fileUploadProperties;

    private PrintFileServiceImpl printFileService;

    @BeforeEach
    void setUp() {
        printFileService = new PrintFileServiceImpl(rustFsClient, fileUploadProperties);
        ReflectionTestUtils.setField(printFileService, "baseMapper", printFileMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorReceivesOwnTreeWithNestedFoldersAndOrphanAtRoot() {
        mockUser(7L, "OPERATOR");
        when(printFileMapper.selectAccessibleFileTree(7L, false)).thenReturn(List.of(
                folder(1L, null, "Models"),
                folder(2L, 1L, "Toys"),
                file(3L, 2L, "cube.gcode"),
                file(4L, 999L, "orphan.gcode")));

        List<FileNodeVO> roots = printFileService.getFileTree();

        verify(printFileMapper).selectAccessibleFileTree(eq(7L), eq(false));
        assertThat(roots).extracting(FileNodeVO::getId).containsExactly(1L, 4L);
        assertThat(roots.get(0).getFolder()).isTrue();
        assertThat(roots.get(0).getChildren()).singleElement()
                .extracting(FileNodeVO::getName).isEqualTo("Toys");
        FileNodeVO toys = roots.get(0).getChildren().get(0);
        assertThat(toys.getChildren()).singleElement()
                .extracting(FileNodeVO::getName).isEqualTo("cube.gcode");
        assertThat(roots.get(1).getFolder()).isFalse();
        assertThat(roots.get(1).getChildren()).isEmpty();
    }

    @Test
    void adminReceivesAllNodesAndCyclicNodesAreKeptAtRoot() {
        mockUser(1L, "ADMIN");
        when(printFileMapper.selectAccessibleFileTree(1L, true)).thenReturn(List.of(
                folder(10L, 11L, "cycle-a"),
                folder(11L, 10L, "cycle-b")));

        List<FileNodeVO> roots = printFileService.getFileTree();

        verify(printFileMapper).selectAccessibleFileTree(eq(1L), eq(true));
        assertThat(roots).extracting(FileNodeVO::getId).containsExactly(10L, 11L);
        assertThat(roots).allSatisfy(node -> assertThat(node.getChildren()).isEmpty());
    }

    private PrintFile folder(Long id, Long parentId, String name) {
        PrintFile folder = new PrintFile();
        folder.setId(id);
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setOriginalName(name);
        folder.setUserId(7L);
        return folder;
    }

    private PrintFile file(Long id, Long parentId, String name) {
        PrintFile file = folder(id, parentId, name);
        file.setIsFolder(false);
        file.setFileSize(123L);
        file.setMaterialType("PLA");
        return file;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
