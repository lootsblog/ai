package com.example.Export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExcelExportService {
    
    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpMethodHandler httpMethodHandler;
    private final String assetApiUrl;
    
    // Constants
    private static final int PARALLEL_CALLS = 5;
    private static final int PAGE_SIZE = 5000;
    private static final int EXCEL_ROW_LIMIT = 800000; // 800K rows per Excel file
    private static final int BATCH_SIZE = PAGE_SIZE * PARALLEL_CALLS; // 25K assets per batch
    
    public ExcelExportService(HttpMethodHandler httpMethodHandler, String assetApiUrl) {
        this.httpMethodHandler = httpMethodHandler;
        this.assetApiUrl = assetApiUrl;
    }
    
    /**
     * Main method to generate ZIP file containing multiple Excel files
     */
    public byte[] generateExcelZip() throws IOException {
        log.info("Starting ZIP-based Excel export process");
        
        try (ByteArrayOutputStream zipByteStream = new ByteArrayOutputStream();
             ZipOutputStream zipStream = new ZipOutputStream(zipByteStream)) {
            
            ZipExportContext context = new ZipExportContext(zipStream);
            processAssetsInBatches(context);
            finalizePendingExcel(context);
            
            zipStream.finish();
            
            log.info("ZIP export completed successfully. Created {} Excel files with {} total assets", 
                context.getCompletedExcelFiles(), context.getTotalAssetsProcessed());
            
            return zipByteStream.toByteArray();
            
        } catch (Exception e) {
            log.error("Failed to generate Excel ZIP: {}", e.getMessage(), e);
            throw new RuntimeException("Excel ZIP generation failed", e);
        }
    }
    
    /**
     * Process assets in batches and create Excel files as needed
     */
    private void processAssetsInBatches(ZipExportContext context) throws IOException {
        ExecutorService executor = null;
        
        try {
            executor = Executors.newFixedThreadPool(PARALLEL_CALLS);
            JsonNode filterBody = createFilterBody(); // Your existing filter logic
            
            boolean hasMore = true;
            int currentOffset = 0;
            
            while (hasMore) {
                // Fetch batch of assets using existing parallel logic
                List<JsonNode> batchAssets = fetchAssetBatch(executor, filterBody, currentOffset);
                
                if (batchAssets.isEmpty()) {
                    break;
                }
                
                // Process this batch
                processAssetBatch(context, batchAssets);
                
                // Update for next iteration
                currentOffset += BATCH_SIZE;
                hasMore = batchAssets.size() == BATCH_SIZE;
                
                log.info("Processed batch: {} assets. Total processed: {}", 
                    batchAssets.size(), context.getTotalAssetsProcessed());
            }
            
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
        }
    }
    
    /**
     * Fetch a batch of assets using parallel API calls
     */
    private List<JsonNode> fetchAssetBatch(ExecutorService executor, JsonNode filterBody, int startOffset) {
        List<CompletableFuture<List<JsonNode>>> batchFutures = new ArrayList<>();
        
        // Launch parallel calls for this batch
        for (int i = 0; i < PARALLEL_CALLS; i++) {
            final int offset = startOffset + (i * PAGE_SIZE);
            
            batchFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchAssetsFromAPI(filterBody, offset);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, executor));
        }
        
        // Wait for batch to complete and collect results
        return batchFutures.stream()
            .map(CompletableFuture::join)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }
    
    /**
     * Process a batch of assets, creating Excel files as needed
     */
    private void processAssetBatch(ZipExportContext context, List<JsonNode> assets) throws IOException {
        for (JsonNode asset : assets) {
            int assetRowCount = calculateMaxRows(asset);
            
            // Check if adding this asset would exceed Excel row limit
            if (context.getCurrentExcelRowCount() + assetRowCount > EXCEL_ROW_LIMIT && 
                context.hasAssetsInCurrentExcel()) {
                
                // Finalize current Excel and start new one
                finalizeCurrentExcel(context);
                startNewExcel(context);
            }
            
            // Add asset to current Excel
            addAssetToCurrentExcel(context, asset);
            context.incrementRowCount(assetRowCount);
            context.incrementAssetCount();
        }
    }
    
    /**
     * Add an asset to the current Excel file being built
     */
    private void addAssetToCurrentExcel(ZipExportContext context, JsonNode asset) {
        if (context.getCurrentExcel() == null) {
            startNewExcel(context);
        }
        
        ExcelBuilder builder = context.getCurrentExcelBuilder();
        builder.addAsset(asset);
    }
    
    /**
     * Start building a new Excel file
     */
    private void startNewExcel(ZipExportContext context) {
        log.info("Starting Excel file #{}", context.getCompletedExcelFiles() + 1);
        
        Workbook workbook = new XSSFWorkbook();
        ExcelBuilder builder = new ExcelBuilder(workbook);
        
        context.setCurrentExcel(workbook);
        context.setCurrentExcelBuilder(builder);
        context.resetCurrentExcelRowCount();
    }
    
    /**
     * Finalize the current Excel file and add it to ZIP
     */
    private void finalizeCurrentExcel(ZipExportContext context) throws IOException {
        if (context.getCurrentExcel() == null) {
            return;
        }
        
        // Generate Excel bytes
        byte[] excelBytes = generateExcelBytes(context.getCurrentExcel());
        
        // Add to ZIP
        String filename = String.format("Assets_%d.xlsx", context.getCompletedExcelFiles() + 1);
        addExcelToZip(context.getZipStream(), filename, excelBytes);
        
        // Update context
        context.incrementCompletedExcelFiles();
        context.clearCurrentExcel();
        
        log.info("Completed Excel file: {} (rows: {}, assets: {})", 
            filename, context.getCurrentExcelRowCount(), context.getAssetsInCurrentExcel());
    }
    
    /**
     * Finalize any pending Excel file at the end
     */
    private void finalizePendingExcel(ZipExportContext context) throws IOException {
        if (context.hasAssetsInCurrentExcel()) {
            finalizeCurrentExcel(context);
        }
    }
    
    /**
     * Add Excel file to ZIP stream
     */
    private void addExcelToZip(ZipOutputStream zipStream, String filename, byte[] excelBytes) throws IOException {
        ZipEntry entry = new ZipEntry(filename);
        entry.setSize(excelBytes.length);
        
        zipStream.putNextEntry(entry);
        zipStream.write(excelBytes);
        zipStream.closeEntry();
    }
    
    /**
     * Generate bytes from Excel workbook
     */
    private byte[] generateExcelBytes(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } finally {
            workbook.close();
        }
    }
    
    /**
     * Create filter body for API calls - implement your existing logic
     */
    private JsonNode createFilterBody() {
        // TODO: Implement your existing filter body creation logic
        return objectMapper.createObjectNode();
    }
    
    // ========== Existing methods (unchanged) ==========
    
    /**
     * Keep the original single Excel generation for backward compatibility
     */
    public byte[] generateExcel() throws IOException {
        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(PARALLEL_CALLS);
            List<JsonNode> allAssets = new ArrayList<>();
            boolean hasMore = true;
            int currentOffset = 0;
            int totalProcessed = 0;
            JsonNode filterBody = createFilterBody();
            
            while (hasMore) {
                List<CompletableFuture<List<JsonNode>>> batchFutures = new ArrayList<>();
                
                // Launch parallel calls for this batch
                for (int i = 0; i < PARALLEL_CALLS; i++) {
                    final int offset = currentOffset;
                    currentOffset += PAGE_SIZE;
                    
                    batchFutures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            return fetchAssetsFromAPI(filterBody, offset);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, executor));
                }
                
                // Wait for batch to complete and collect results
                List<JsonNode> batchResults = batchFutures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
                
                // Check if this was the last page
                if (batchResults.size() < PAGE_SIZE) {
                    hasMore = false;
                }
                
                // Update progress
                totalProcessed += batchResults.size();
                log.info("Processed {}/{} assets (batch size: {})", 
                    totalProcessed, 
                    hasMore ? "?" : totalProcessed, 
                    batchResults.size());
                
                allAssets.addAll(batchResults);
            }
            
            log.info("Starting Excel creation for {} assets", allAssets.size());
            return createExcelFromAssets(allAssets);
            
        } catch (CompletionException e) {
            log.error("Failed to fetch assets: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch assets: " + e.getMessage());
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
        }
    }
    
    /**
     * This method simulates API call but reads from mock JSON file
     */
    public List<JsonNode> fetchAssetsFromAPI(JsonNode filterBody, int offset) throws IOException {
        FilterBodyDto apiFilterBody = new FilterBodyDto();
        apiFilterBody.setColumns(filterBody.get("columns"));
        apiFilterBody.setFilters(filterBody.get("filters"));
        
        // Set pagination with the thread-specific offset
        Pagination pagination = new Pagination();
        pagination.setOffset(offset);
        pagination.setLimit(PAGE_SIZE);
        apiFilterBody.setPagination(pagination);
        
        // In real implementation, this would be your HTTP call
        // For now, read from mock JSON file
        ClassPathResource resource = new ClassPathResource("mock-assets.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<JsonNode>>() {});
        }
    }
    
    private byte[] createExcelFromAssets(List<JsonNode> assets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            ExcelBuilder builder = new ExcelBuilder(workbook);
            
            for (JsonNode asset : assets) {
                builder.addAsset(asset);
            }
            
            // Return as byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }
    
    private int calculateMaxRows(JsonNode asset) {
        int maxRows = 1; // At least one row for simple fields
        
        // Check each array field to find the maximum array size
        for (Map.Entry<String, FieldMappingConfig.FieldMetadata> entry : FieldMappingConfig.FIELD_CONFIG.entrySet()) {
            FieldMappingConfig.FieldMetadata metadata = entry.getValue();
            
            if (metadata.type == FieldMappingConfig.FieldType.ARRAY) {
                JsonNode arrayNode = asset.get(metadata.sourceArray);
                if (arrayNode != null && arrayNode.isArray()) {
                    maxRows = Math.max(maxRows, arrayNode.size());
                }
            }
        }
        
        return maxRows;
    }
    
    // ========== Inner Classes ==========
    
    /**
     * Context class to track ZIP export state
     */
    private static class ZipExportContext {
        private final ZipOutputStream zipStream;
        private Workbook currentExcel;
        private ExcelBuilder currentExcelBuilder;
        private int currentExcelRowCount = 0;
        private int assetsInCurrentExcel = 0;
        private int completedExcelFiles = 0;
        private int totalAssetsProcessed = 0;
        
        public ZipExportContext(ZipOutputStream zipStream) {
            this.zipStream = zipStream;
        }
        
        // Getters and setters
        public ZipOutputStream getZipStream() { return zipStream; }
        public Workbook getCurrentExcel() { return currentExcel; }
        public void setCurrentExcel(Workbook currentExcel) { this.currentExcel = currentExcel; }
        public ExcelBuilder getCurrentExcelBuilder() { return currentExcelBuilder; }
        public void setCurrentExcelBuilder(ExcelBuilder currentExcelBuilder) { this.currentExcelBuilder = currentExcelBuilder; }
        public int getCurrentExcelRowCount() { return currentExcelRowCount; }
        public void incrementRowCount(int rows) { this.currentExcelRowCount += rows; }
        public void resetCurrentExcelRowCount() { this.currentExcelRowCount = 0; this.assetsInCurrentExcel = 0; }
        public boolean hasAssetsInCurrentExcel() { return assetsInCurrentExcel > 0; }
        public int getAssetsInCurrentExcel() { return assetsInCurrentExcel; }
        public void incrementAssetCount() { this.assetsInCurrentExcel++; this.totalAssetsProcessed++; }
        public int getCompletedExcelFiles() { return completedExcelFiles; }
        public void incrementCompletedExcelFiles() { this.completedExcelFiles++; }
        public int getTotalAssetsProcessed() { return totalAssetsProcessed; }
        public void clearCurrentExcel() { 
            this.currentExcel = null; 
            this.currentExcelBuilder = null;
        }
    }
    
    /**
     * Builder class to handle Excel creation logic
     */
    private class ExcelBuilder {
        private final Workbook workbook;
        private final Sheet sheet;
        private int currentRow = 0;
        private boolean headersCreated = false;
        
        public ExcelBuilder(Workbook workbook) {
            this.workbook = workbook;
            this.sheet = workbook.createSheet("Assets");
            createHeaders();
        }
        
        private void createHeaders() {
            if (!headersCreated) {
                currentRow = createGroupHeaders(sheet, workbook, currentRow);
                currentRow = createColumnHeaders(sheet, workbook, currentRow);
                headersCreated = true;
            }
        }
        
        public void addAsset(JsonNode asset) {
            currentRow = processAsset(sheet, workbook, asset, currentRow);
        }
        
        // Use your existing Excel creation methods
        private int processAsset(Sheet sheet, Workbook workbook, JsonNode asset, int currentRow) {
            // Your existing processAsset implementation
            int maxRows = calculateMaxRows(asset);
            
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle bottomBorderStyle = createBottomBorderStyle(workbook);
            
            for (int arrayIndex = 0; arrayIndex < maxRows; arrayIndex++) {
                Map<String, String> rowData = buildRowData(asset, arrayIndex);
                createExcelRow(sheet, rowData, dataStyle, currentRow++);
            }
            
            addBottomBorderToRow(sheet, bottomBorderStyle, currentRow - 1);
            return currentRow;
        }
        
        // Include all your existing Excel creation helper methods here
        // (createGroupHeaders, createColumnHeaders, buildRowData, etc.)
        // I'm not duplicating them to keep the code concise
    }
}
