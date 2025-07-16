package com.example.Export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExcelExportServiceTest {

    @Mock
    private HttpMethodHandler httpMethodHandler;

    @Mock
    private RegionEndpointService regionEndpointService;

    @Mock
    private TaskExecutor exportTaskExecutor;

    private ExcelExportService excelExportService;
    private ObjectMapper objectMapper;
    private JsonNode mockFilterBody;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        excelExportService = new ExcelExportService(httpMethodHandler, regionEndpointService, exportTaskExecutor);
        mockFilterBody = objectMapper.createObjectNode().put("test", "filter");

        // Setup external service mocks (always successful)
        when(regionEndpointService.getNLBEndpoint()).thenReturn("http://test-url/");
        
        // Mock TaskExecutor to run synchronously
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(exportTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    void testStartExport_SmallDataset_ReturnsExcelFile() throws Exception {
        // Arrange - Small dataset (< 5000 assets)
        List<JsonNode> smallAssets = createMockAssets(100);
        mockHttpResponse(smallAssets);

        // Act
        ExcelExportService.ExportHelperDto result = excelExportService.startExport(mockFilterBody);

        // Assert
        assertNotNull(result);
        assertEquals("EXCEL", result.getFileType());
        assertTrue(result.getFileName().contains("mediaasset_export_"));
        assertTrue(result.getFileName().endsWith(".xlsx"));
        assertNotNull(result.getExcelData());
        assertTrue(result.getExcelData().length > 0);

        // Verify single API call made
        verify(httpMethodHandler, times(1)).handleHttpExchange(
            anyString(), eq("POST"), any(HttpEntity.class), eq(ResponseDto.class));
    }

    @Test
    void testStartExport_LargeDataset_ReturnsZipFile() throws Exception {
        // Arrange - Large dataset (>= 5000 assets, will create multiple Excel files)
        List<JsonNode> batch1 = createMockAssets(5000); // First page (triggers ZIP)
        List<JsonNode> batch2 = createMockAssets(5000); // Second batch
        List<JsonNode> batch3 = createMockAssets(2000); // Final batch
        List<JsonNode> emptyBatch = Collections.emptyList(); // End signal

        // Mock sequential API responses
        when(httpMethodHandler.handleHttpExchange(anyString(), eq("POST"), any(HttpEntity.class), eq(ResponseDto.class)))
            .thenReturn(createMockResponseEntity(batch1))
            .thenReturn(createMockResponseEntity(batch2))
            .thenReturn(createMockResponseEntity(batch3))
            .thenReturn(createMockResponseEntity(emptyBatch));

        // Act
        ExcelExportService.ExportHelperDto result = excelExportService.startExport(mockFilterBody);

        // Assert
        assertNotNull(result);
        assertEquals("ZIP", result.getFileType());
        assertTrue(result.getFileName().contains("mediaasset_export_"));
        assertTrue(result.getFileName().endsWith(".zip"));
        assertNotNull(result.getExcelData());
        assertTrue(result.getExcelData().length > 0);

        // Verify multiple API calls were made (parallel calls for batches)
        verify(httpMethodHandler, atLeast(4)).handleHttpExchange(
            anyString(), eq("POST"), any(HttpEntity.class), eq(ResponseDto.class));
    }

    @Test
    void testStartExport_EmptyDataset_ReturnsExcelFile() throws Exception {
        // Arrange - Empty dataset
        List<JsonNode> emptyAssets = Collections.emptyList();
        mockHttpResponse(emptyAssets);

        // Act
        ExcelExportService.ExportHelperDto result = excelExportService.startExport(mockFilterBody);

        // Assert
        assertNotNull(result);
        assertEquals("EXCEL", result.getFileType());
        assertTrue(result.getFileName().endsWith(".xlsx"));
        assertNotNull(result.getExcelData());
        assertTrue(result.getExcelData().length > 0); // Should have headers even with no data
    }

    @Test
    void testStartExport_ExactPageSizeDataset_ReturnsZipFile() throws Exception {
        // Arrange - Exactly 5000 assets (boundary case)
        List<JsonNode> exactPageSize = createMockAssets(5000);
        mockHttpResponse(exactPageSize);

        // Act
        ExcelExportService.ExportHelperDto result = excelExportService.startExport(mockFilterBody);

        // Assert
        assertEquals("ZIP", result.getFileType()); // Should be ZIP since size >= PAGE_SIZE
        assertTrue(result.getFileName().endsWith(".zip"));
    }

    @Test
    void testStartExport_WithComplexAssetData() throws Exception {
        // Arrange - Assets with arrays, nulls, dates, CSV values
        List<JsonNode> complexAssets = createComplexMockAssets();
        mockHttpResponse(complexAssets);

        // Act
        ExcelExportService.ExportHelperDto result = excelExportService.startExport(mockFilterBody);

        // Assert
        assertNotNull(result);
        assertEquals("EXCEL", result.getFileType());
        assertNotNull(result.getExcelData());
        assertTrue(result.getExcelData().length > 0);
    }

    @Test
    void testFetchAssetsFromAPI_SuccessfulCall() throws Exception {
        // Arrange
        List<JsonNode> mockAssets = createMockAssets(10);
        mockHttpResponse(mockAssets);

        // Act
        List<JsonNode> result = excelExportService.fetchAssetsFromAPI(mockFilterBody, 0);

        // Assert
        assertNotNull(result);
        assertEquals(10, result.size());
        
        // Verify correct API call
        verify(httpMethodHandler, times(1)).handleHttpExchange(
            eq("http://test-url/export"), eq("POST"), any(HttpEntity.class), eq(ResponseDto.class));
        verify(regionEndpointService, times(1)).getNLBEndpoint();
    }

    @Test
    void testFetchAssetsFromAPI_WithOffset() throws Exception {
        // Arrange
        List<JsonNode> mockAssets = createMockAssets(5);
        mockHttpResponse(mockAssets);

        // Act
        List<JsonNode> result = excelExportService.fetchAssetsFromAPI(mockFilterBody, 100);

        // Assert
        assertNotNull(result);
        assertEquals(5, result.size());
        
        // Verify offset was used (would be in the HTTP entity body)
        verify(httpMethodHandler, times(1)).handleHttpExchange(
            anyString(), eq("POST"), any(HttpEntity.class), eq(ResponseDto.class));
    }

    // Helper methods
    private List<JsonNode> createMockAssets(int count) {
        List<JsonNode> assets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            assets.add(objectMapper.createObjectNode()
                .put("id", "asset_" + i)
                .put("title", "Asset Title " + i)
                .put("type", "video")
                .put("releaseDate", "2023-01-01T00:00:00Z"));
        }
        return assets;
    }

    private List<JsonNode> createComplexMockAssets() {
        List<JsonNode> assets = new ArrayList<>();
        
        // Asset with arrays and various field types
        JsonNode complexAsset = objectMapper.createObjectNode()
            .put("id", "complex_1")
            .put("title", "Complex Asset")
            .put("releaseDate", "2023-01-01T00:00:00Z")
            .set("tags", objectMapper.createArrayNode().add("action").add("drama"))
            .set("cast", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                    .put("personName", "Actor 1")
                    .put("role", "Lead")
                    .put("characterName", "Hero"))
                .add(objectMapper.createObjectNode()
                    .put("personName", "Actor 2")
                    .put("role", "Supporting")
                    .put("characterName", "Sidekick")))
            .set("licenseList", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                    .put("licenseId", "LIC001")
                    .put("start", "2023-01-01T00:00:00Z")
                    .put("end", "2024-01-01T00:00:00Z")));

        // Asset with null values
        JsonNode nullAsset = objectMapper.createObjectNode()
            .put("id", "null_asset")
            .putNull("title")
            .putNull("description");

        // Asset with empty arrays
        JsonNode emptyArrayAsset = objectMapper.createObjectNode()
            .put("id", "empty_arrays")
            .set("tags", objectMapper.createArrayNode())
            .set("cast", objectMapper.createArrayNode());

        assets.add(complexAsset);
        assets.add(nullAsset);
        assets.add(emptyArrayAsset);
        return assets;
    }

    private void mockHttpResponse(List<JsonNode> assets) throws Exception {
        ResponseEntity<ResponseDto> mockResponse = createMockResponseEntity(assets);
        when(httpMethodHandler.handleHttpExchange(anyString(), eq("POST"), any(HttpEntity.class), eq(ResponseDto.class)))
            .thenReturn(mockResponse);
    }

    private ResponseEntity<ResponseDto> createMockResponseEntity(List<JsonNode> assets) {
        // Create mock response structure: ResponseDto → getRsp() → getPayload() → get("assets")
        Map<String, Object> payload = new HashMap<>();
        payload.put("assets", assets);

        ResponseDto.Rsp mockRsp = mock(ResponseDto.Rsp.class);
        when(mockRsp.getPayload()).thenReturn(payload);

        ResponseDto mockResponseDto = mock(ResponseDto.class);
        when(mockResponseDto.getRsp()).thenReturn(mockRsp);

        ResponseEntity<ResponseDto> mockResponseEntity = mock(ResponseEntity.class);
        when(mockResponseEntity.getBody()).thenReturn(mockResponseDto);

        return mockResponseEntity;
    }
}
