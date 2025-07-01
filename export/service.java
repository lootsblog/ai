package com.example.Export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

@Slf4j
@Service
public class ExcelExportService {
    
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
     * @param filterBody The filter criteria received from API call
     * @return ZIP file as byte array containing multiple Excel files
     */
    public byte[] generateExcelZip(JsonNode filterBody) throws IOException {
        log.info("Starting ZIP-based Excel export process");
        
        try (ByteArrayOutputStream zipByteStream = new ByteArrayOutputStream();
             ZipOutputStream zipStream = new ZipOutputStream(zipByteStream)) {
            
            ZipExportContext context = new ZipExportContext(zipStream);
            processAssetsInBatches(context, filterBody);
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
     * Process assets in batches continuously until all data is fetched
     */
    private void processAssetsInBatches(ZipExportContext context, JsonNode filterBody) throws IOException {
        boolean hasMore = true;
        int currentOffset = 0;
        
        while (hasMore) {
            // Fetch batch of assets using parallel API calls
            List<JsonNode> batchAssets = fetchAssetBatch(filterBody, currentOffset);
            
            if (batchAssets.isEmpty()) {
                break;
            }
            
            // Process this batch - create Excel files as needed
            processAssetBatch(context, batchAssets);
            
            // Update for next iteration
            currentOffset += BATCH_SIZE;
            hasMore = batchAssets.size() == BATCH_SIZE;
            
            log.info("Processed batch: {} assets. Total processed: {}", 
                batchAssets.size(), context.getTotalAssetsProcessed());
        }
    }
    
    /**
     * Fetch a batch of assets using parallel API calls (your existing logic)
     */
    private List<JsonNode> fetchAssetBatch(JsonNode filterBody, int startOffset) {
        ExecutorService executor = null;
        
        try {
            executor = Executors.newFixedThreadPool(PARALLEL_CALLS);
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
                
        } catch (CompletionException e) {
            log.error("Failed to fetch asset batch: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch asset batch", e);
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
        }
    }
    
    /**
     * Process a batch of assets, creating Excel files as needed based on row limits
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
     * Finalize any pending Excel file at the end of processing
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
     * Generate bytes from Excel workbook and properly dispose resources
     */
    private byte[] generateExcelBytes(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } finally {
            workbook.close();
        }
    }
    
    // ========== Existing methods (unchanged but with proper error handling) ==========
    
    /**
     * Keep the original single Excel generation for backward compatibility
     */
    public byte[] generateExcel(JsonNode filterBody) throws IOException {
        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(PARALLEL_CALLS);
            List<JsonNode> allAssets = new ArrayList<>();
            boolean hasMore = true;
            int currentOffset = 0;
            int totalProcessed = 0;
            
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
                if (batchResults.size() < BATCH_SIZE) {
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
     * API call to fetch assets with pagination
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
        // ResponseEntity<List<JsonNode>> response = restTemplate.exchange(...)
        
        // For now, read from mock JSON file
        ClassPathResource resource = new ClassPathResource("mock-assets.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<JsonNode>>() {});
        }
    }
    
    /**
     * Create single Excel file from all assets (backward compatibility)
     */
    private byte[] createExcelFromAssets(List<JsonNode> assets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            ExcelBuilder builder = new ExcelBuilder(workbook);
            
            for (JsonNode asset : assets) {
                builder.addAsset(asset);
            }
            
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                workbook.write(outputStream);
                return outputStream.toByteArray();
            }
        }
    }
    
    /**
     * Calculate maximum rows needed for an asset (your existing logic)
     */
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
     * Context class to track ZIP export state - encapsulates all state variables
     */
    @Data
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
        
        public void incrementRowCount(int rows) { 
            this.currentExcelRowCount += rows; 
        }
        
        public void resetCurrentExcelRowCount() { 
            this.currentExcelRowCount = 0; 
            this.assetsInCurrentExcel = 0; 
        }
        
        public boolean hasAssetsInCurrentExcel() { 
            return assetsInCurrentExcel > 0; 
        }
        
        public void incrementAssetCount() { 
            this.assetsInCurrentExcel++; 
            this.totalAssetsProcessed++; 
        }
        
        public void incrementCompletedExcelFiles() { 
            this.completedExcelFiles++; 
        }
        
        public void clearCurrentExcel() { 
            this.currentExcel = null; 
            this.currentExcelBuilder = null;
        }
    }
    
    /**
     * Builder class to handle Excel creation logic - encapsulates Excel file building
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
        
        private int processAsset(Sheet sheet, Workbook workbook, JsonNode asset, int currentRow) {
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
        
        private Map<String, String> buildRowData(JsonNode asset, int arrayIndex) {
            Map<String, String> rowData = new HashMap<>();
            
            for (Map.Entry<String, FieldMappingConfig.FieldMetadata> entry : FieldMappingConfig.FIELD_CONFIG.entrySet()) {
                String fieldName = entry.getKey();
                FieldMappingConfig.FieldMetadata metadata = entry.getValue();
                
                String value = extractValueBasedOnType(asset, fieldName, metadata, arrayIndex);
                rowData.put(fieldName, value);
            }
            
            return rowData;
        }
        
        private String extractValueBasedOnType(JsonNode asset, String fieldName, FieldMappingConfig.FieldMetadata metadata, int arrayIndex) {
            switch (metadata.type) {
                case SIMPLE:
                    return arrayIndex == 0 ? getSimpleValue(asset, fieldName) : "";
                case ARRAY:
                    return getArrayValue(asset, metadata.sourceArray, metadata.sourceField, arrayIndex);
                case COMMA_SEPARATED:
                    return arrayIndex == 0 ? getCommaSeparatedValue(asset, fieldName) : "";
                default:
                    return "";
            }
        }
        
        private String getSimpleValue(JsonNode asset, String fieldName) {
            JsonNode value = asset.get(fieldName);
            if (value == null || value.isNull()) {
                return "";
            }
            
            String stringValue = value.asText();
            
            if (fieldName.contains("Date") && stringValue.contains("T")) {
                return stringValue.replace("T", " ").replace("Z", "");
            }
            
            return stringValue;
        }
        
        private String getArrayValue(JsonNode asset, String sourceArray, String sourceField, int arrayIndex) {
            JsonNode arrayNode = asset.get(sourceArray);
            if (arrayNode == null || !arrayNode.isArray() || arrayIndex >= arrayNode.size()) {
                return "";
            }
            
            JsonNode item = arrayNode.get(arrayIndex);
            if (item == null) {
                return "";
            }
            
            JsonNode fieldValue = item.get(sourceField);
            if (fieldValue == null || fieldValue.isNull()) {
                return "";
            }
            
            String stringValue = fieldValue.asText();
            
            if ((sourceField.equals("start") || sourceField.equals("end")) && stringValue.contains("T")) {
                return stringValue.replace("T", " ").replace("Z", "");
            }
            
            return stringValue;
        }
        
        private String getCommaSeparatedValue(JsonNode asset, String fieldName) {
            JsonNode value = asset.get(fieldName);
            if (value == null || value.isNull()) {
                return "";
            }
            
            if (value.isArray()) {
                List<String> items = new ArrayList<>();
                for (JsonNode item : value) {
                    items.add(item.asText());
                }
                return String.join(", ", items);
            }
            
            return value.asText();
        }
        
        private void createExcelRow(Sheet sheet, Map<String, String> rowData, CellStyle dataStyle, int rowIndex) {
            Row row = sheet.createRow(rowIndex);
            String[] fields = FieldMappingConfig.getFieldsInOrder();
            
            for (int i = 0; i < fields.length; i++) {
                Cell cell = row.createCell(i);
                cell.setCellStyle(dataStyle);
                
                String value = rowData.get(fields[i]);
                cell.setCellValue(value != null ? value : "");
            }
        }
        
        private void addBottomBorderToRow(Sheet sheet, CellStyle borderStyle, int rowIndex) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                String[] fields = FieldMappingConfig.getFieldsInOrder();
                for (int i = 0; i < fields.length; i++) {
                    Cell cell = row.getCell(i);
                    if (cell != null) {
                        cell.setCellStyle(borderStyle);
                    }
                }
            }
        }
    }
    
    // ========== Style Creation Methods ==========
    
    private int createGroupHeaders(Sheet sheet, Workbook workbook, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        String[] groups = FieldMappingConfig.getGroupsInOrder();
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        
        int colIndex = 0;
        for (String group : groups) {
            CellStyle groupStyle = createGroupHeaderStyle(workbook, group);
            
            int groupStartCol = colIndex;
            int groupColCount = FieldMappingConfig.countColumnsInGroup(fields, group);
            
            if (groupColCount > 0) {
                Cell cell = row.createCell(groupStartCol);
                cell.setCellValue(group);
                cell.setCellStyle(groupStyle);
                
                if (groupColCount > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(
                        rowIndex, rowIndex, groupStartCol, groupStartCol + groupColCount - 1));
                }
                
                colIndex += groupColCount;
            }
        }
        
        return rowIndex + 1;
    }
    
    private int createColumnHeaders(Sheet sheet, Workbook workbook, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        
        for (int i = 0; i < fields.length; i++) {
            Cell cell = row.createCell(i);
            FieldMappingConfig.FieldMetadata metadata = FieldMappingConfig.FIELD_CONFIG.get(fields[i]);
            String columnName = metadata != null ? metadata.columnName : fields[i];
            cell.setCellValue(columnName);
            cell.setCellStyle(columnHeaderStyle);
        }
        
        return rowIndex + 1;
    }
    
    private CellStyle createGroupHeaderStyle(Workbook workbook, String group) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        
        IndexedColors color = FieldMappingConfig.GROUP_COLORS.getOrDefault(
            group, IndexedColors.LIGHT_BLUE);
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
    
    private CellStyle createColumnHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
    
    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
    
    private CellStyle createBottomBorderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setBorderBottom(BorderStyle.THICK);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
}
