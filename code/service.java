package com.example.Export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ExcelExportService {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // This method simulates API call but reads from mock JSON file
    public List<JsonNode> fetchAssetsFromAPI() throws IOException {
        // In real implementation, this would be:
        // RestTemplate restTemplate = new RestTemplate();
        // ResponseEntity<List<JsonNode>> response = restTemplate.exchange(
        //     "https://api.example.com/assets", 
        //     HttpMethod.GET, 
        //     null, 
        //     new ParameterizedTypeReference<List<JsonNode>>() {}
        // );
        // return response.getBody();
        
        // For now, read from mock JSON file
        ClassPathResource resource = new ClassPathResource("mock-assets.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<JsonNode>>() {});
        }
    }
    
    public byte[] generateExcel() throws IOException {
        List<JsonNode> assets = fetchAssetsFromAPI();
        
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Assets");
            
            int currentRow = 0;
            
            // Create headers
            currentRow = createGroupHeaders(sheet, workbook, currentRow);
            currentRow = createColumnHeaders(sheet, workbook, currentRow);
            
            // Process each asset
            for (JsonNode asset : assets) {
                currentRow = processAsset(sheet, workbook, asset, currentRow);
            }
            
            // Auto-size columns
            autoSizeColumns(sheet);
            
            // Return as byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }
    
    private int processAsset(Sheet sheet, Workbook workbook, JsonNode asset, int currentRow) {
        // Calculate maximum rows needed for this asset
        int maxRows = calculateMaxRows(asset);
        
        // Create style for data cells
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle bottomBorderStyle = createBottomBorderStyle(workbook);
        
        // Process each row
        int assetStartRow = currentRow;
        for (int arrayIndex = 0; arrayIndex < maxRows; arrayIndex++) {
            Map<String, String> rowData = buildRowData(asset, arrayIndex);
            createExcelRow(sheet, rowData, dataStyle, currentRow++);
        }
        
        // Add bottom border to last row
        addBottomBorderToRow(sheet, bottomBorderStyle, currentRow - 1);
        
        return currentRow;
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
    
    private Map<String, String> buildRowData(JsonNode asset, int arrayIndex) {
        Map<String, String> rowData = new HashMap<>();
        
        // Process each field according to its configuration
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
                // Only fill simple fields in first row
                return arrayIndex == 0 ? getSimpleValue(asset, fieldName) : "";
                
            case ARRAY:
                // Get value from array at specified index
                return getArrayValue(asset, metadata.sourceArray, metadata.sourceField, arrayIndex);
                
            case COMMA_SEPARATED:
                // Only fill CSV fields in first row
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
        
        // Format dates - remove T and Z, add space
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
        
        // Format dates for license fields
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
            // Get group style with appropriate color
            CellStyle groupStyle = createGroupHeaderStyle(workbook, group);
            
            // Determine column span for this group
            int groupStartCol = colIndex;
            int groupColCount = FieldMappingConfig.countColumnsInGroup(fields, group);
            
            if (groupColCount > 0) {
                // Create and style the cell
                Cell cell = row.createCell(groupStartCol);
                cell.setCellValue(group);
                cell.setCellStyle(groupStyle);
                
                // Merge cells if needed
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
    
    private void autoSizeColumns(Sheet sheet) {
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        for (int i = 0; i < fields.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }
    
    // Style creation methods with group-specific colors
    private CellStyle createGroupHeaderStyle(Workbook workbook, String group) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        
        // Get color for this group
        IndexedColors color = FieldMappingConfig.GROUP_COLORS.getOrDefault(
            group, IndexedColors.LIGHT_BLUE);
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        
        // Add borders and alignment
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
