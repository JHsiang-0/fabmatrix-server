package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.entity.PrintFile;
import com.example.farm.service.PrintFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrintFileFolderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrintFileService printFileService;

    @Test
    void authenticatedOperatorCanRenameAndMoveFolder() throws Exception {
        PrintFile renamed = folder(30L, null, "Renamed");
        when(printFileService.renameFolder(30L, "Renamed")).thenReturn(renamed);
        when(printFileService.moveFolder(30L, 40L)).thenReturn(folder(30L, 40L, "Renamed"));

        mockMvc.perform(put("/api/v1/print-files/folder/30/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderName\":\"Renamed\"}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.originalName").value("Renamed"));

        mockMvc.perform(put("/api/v1/print-files/folder/30/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":40}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(40));

        verify(printFileService).renameFolder(30L, "Renamed");
        verify(printFileService).moveFolder(30L, 40L);
    }

    @Test
    void moveAcceptsNullParentForRoot() throws Exception {
        when(printFileService.moveFolder(30L, null)).thenReturn(folder(30L, null, "Source"));

        mockMvc.perform(put("/api/v1/print-files/folder/30/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").doesNotExist());

        verify(printFileService).moveFolder(30L, null);
    }

    @Test
    void unauthenticatedCannotUseFolderOperations() throws Exception {
        mockMvc.perform(put("/api/v1/print-files/folder/30/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderName\":\"Renamed\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(printFileService);
    }

    @Test
    void invalidFolderPayloadIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(put("/api/v1/print-files/folder/30/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderName\":\"bad/name\"}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/v1/print-files/folder/30/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":0}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(printFileService);
    }

    @Test
    void authenticatedOperatorCanBatchMoveFilesAndReceiveItemResults() throws Exception {
        PrintFileService.BatchMoveResult result = new PrintFileService.BatchMoveResult();
        result.setTotalCount(2);
        result.setMovedCount(1);
        result.setFailedCount(1);
        result.setItems(java.util.List.of(
                new PrintFileService.BatchMoveItemResult(20L, true, "移动成功"),
                new PrintFileService.BatchMoveItemResult(21L, false, "文件已经位于目标目录")));
        result.setMessage("批量移动完成：成功 1 个，失败 1 个");
        when(printFileService.batchMoveFiles(java.util.List.of(20L, 21L), 40L)).thenReturn(result);

        mockMvc.perform(put("/api/v1/print-files/batch/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[20,21],\"parentId\":40}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.movedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(20))
                .andExpect(jsonPath("$.data.items[1].success").value(false));

        verify(printFileService).batchMoveFiles(java.util.List.of(20L, 21L), 40L);
    }

    @Test
    void batchMoveAcceptsNullParentForRoot() throws Exception {
        PrintFileService.BatchMoveResult result = new PrintFileService.BatchMoveResult();
        result.setTotalCount(1);
        result.setMovedCount(1);
        result.setMessage("批量移动完成：成功 1 个，失败 0 个");
        when(printFileService.batchMoveFiles(java.util.List.of(20L), null)).thenReturn(result);

        mockMvc.perform(put("/api/v1/print-files/batch/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[20],\"parentId\":null}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movedCount").value(1));

        verify(printFileService).batchMoveFiles(java.util.List.of(20L), null);
    }

    @Test
    void invalidBatchMovePayloadIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(put("/api/v1/print-files/batch/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"parentId\":0}")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(printFileService);
    }

    private PrintFile folder(Long id, Long parentId, String name) {
        PrintFile folder = new PrintFile();
        folder.setId(id);
        folder.setParentId(parentId);
        folder.setIsFolder(true);
        folder.setIsDefaultFolder(false);
        folder.setOriginalName(name);
        folder.setUserId(1L);
        return folder;
    }
}
