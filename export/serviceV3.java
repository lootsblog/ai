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
            // Fetch batch of assets using existing parallel logic
            List<JsonNode> batchAssets = fetchAssetBatch(filterBody, currentOffset);
            
            if (batchAssets.isEmpty()) {
                break;
            }
            
            // Process this batch using existing logic
            processAssetBatch(context, batchAssets);
            
            // Update for next iteration
            currentOffset += BATCH_SIZE;
            hasMore = batchAssets.size() == BATCH_SIZE;
            
            log.info("Processed batch: {} assets. Total processed: {}", 
                batchAssets.size(), context.getTotalAssetsProcessed());
        }
    }
    
    /**
     * Fetch a batch of assets using existing parallel API call logic
     */
    private List<JsonNode> fetchAssetBatch(JsonNode filterBody, int startOffset) {
        ExecutorService executor = null;
        
        try {
            executor = Executors.newFixedThreadPool(PARALLEL_CALLS);
            List<CompletableFuture<List<JsonNode>>> batchFutures = new ArrayList<>();
            
            // Launch parallel calls - reusing existing pattern
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
            
            // Wait and collect results - existing pattern
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
     * Process a batch of assets using EXISTING processAsset method
     */
    private void processAssetBatch(ZipExportContext context, List<JsonNode> assets) throws IOException {
        for (JsonNode asset : assets) {
            // Use existing calculateMaxRows method
            int assetRowCount = calculateMaxRows(asset);
            
            // Check if adding this asset would exceed Excel row limit
            if (context.getCurrentExcelRowCount() + assetRowCount > EXCEL_ROW_LIMIT && 
                context.hasAssetsInCurrentExcel()) {
                
                // Finalize current Excel and start new one
                finalizeCurrentExcel(context);
                startNewExcel(context);
            }
            
            // Ensure we have an Excel file to work with
            if (context.getCurrentExcel() == null) {
                startNewExcel(context);
            }
            
            // Use EXISTING processAsset method - no rewriting!
            int newRowIndex = processAsset(context.getCurrentSheet(), 
                context.getCurrentExcel(), asset, context.getCurrentRow());
            
            // Update context
            context.setCurrentRow(newRowIndex);
            context.incrementRowCount(assetRowCount);
            context.incrementAssetCount();
        }
    }
    
    /**
     * Start building a new Excel file using EXISTING header creation methods
     */
    private void startNewExcel(ZipExportContext context) {
        log.info("Starting Excel file #{}", context.getCompletedExcelFiles() + 1);
        
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Assets");
        
        // Use EXISTING methods for header creation - no duplication!
        int currentRow = 0;
        currentRow = createGroupHeaders(sheet, workbook, currentRow);
        currentRow = createColumnHeaders(sheet, workbook, currentRow);
        
        // Set up context
        context.setCurrentExcel(workbook);
        context.setCurrentSheet(sheet);
        context.setCurrentRow(currentRow);
        context.resetCurrentExcelRowCount();
    }
    
    /**
     * Finalize the current Excel file and add it to ZIP
     */
    private void finalizeCurrentExcel(ZipExportContext context) throws IOException {
        if (context.getCurrentExcel() == null) {
            return;
        }
        
        // Generate Excel bytes using existing logic
        byte[] excelBytes;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            context.getCurrentExcel().write(outputStream);
            excelBytes = outputStream.toByteArray();
        } finally {
            context.getCurrentExcel().close();
        }
        
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
    
    // ========== Existing methods (unchanged - keep as is) ==========
    
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
                
                List<JsonNode> batchResults = batchFutures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
                
                if (batchResults.size() < PAGE_SIZE) {
                    hasMore = false;
                }
                
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
     * Your existing API call method - unchanged
     */
    public List<JsonNode> fetchAssetsFromAPI(JsonNode filterBody, int offset) throws IOException {
        FilterBodyDto apiFilterBody = new FilterBodyDto();
        apiFilterBody.setColumns(filterBody.get("columns"));
        apiFilterBody.setFilters(filterBody.get("filters"));
        
        Pagination pagination = new Pagination();
        pagination.setOffset(offset);
        pagination.setLimit(PAGE_SIZE);
        apiFilterBody.setPagination(pagination);
        
        // Your existing API call logic
        ClassPathResource resource = new ClassPathResource("mock-assets.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<JsonNode>>() {});
        }
    }
    
    /**
     * Your existing Excel creation method - unchanged
     */
    private byte[] createExcelFromAssets(List<JsonNode> assets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Assets");
            
            int currentRow = 0;
            
            // Use existing methods
            currentRow = createGroupHeaders(sheet, workbook, currentRow);
            currentRow = createColumnHeaders(sheet, workbook, currentRow);
            
            // Process each asset using existing method
            for (JsonNode asset : assets) {
                currentRow = processAsset(sheet, workbook, asset, currentRow);
            }
            
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                workbook.write(outputStream);
                return outputStream.toByteArray();
            }
        }
    }
    
    // ========== ALL YOUR EXISTING METHODS STAY EXACTLY THE SAME ==========
    // Keep processAsset, calculateMaxRows, buildRowData, createGroupHeaders, 
    // createColumnHeaders, and all style methods exactly as they are!
    
    private int calculateMaxRows(JsonNode asset) {
        int maxRows = 1;
        
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
    
    // ========== ZIP Export Context ==========
    
    @Data
    private static class ZipExportContext {
        private final ZipOutputStream zipStream;
        private Workbook currentExcel;
        private Sheet currentSheet;
        private int currentRow = 0;
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
            this.currentSheet = null;
            this.currentRow = 0;
        }
    }
}
