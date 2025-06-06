package com.example.Export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
public class ExcelExportService {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public List<JsonNode> fetchAssetsFromAPI() throws IOException {
        ClassPathResource resource = new ClassPathResource("mock-assets.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<JsonNode>>() {});
        }
    }
    
    public byte[] generateExcel() throws IOException {
        List<JsonNode> assets = fetchAssetsFromAPI();
        
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Assets");
            
            // Create styles
            CellStyle groupHeaderStyle = createGroupHeaderStyle(workbook);
            CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle bottomBorderStyle = createBottomBorderStyle(workbook);
            
            int currentRow = 0;
            
            // Create headers
            currentRow = createGroupHeaders(sheet, groupHeaderStyle, currentRow);
            currentRow = createColumnHeaders(sheet, columnHeaderStyle, currentRow);
            
            // Process each asset with simplified logic
            for (JsonNode asset : assets) {
                currentRow = processAssetSimplified(sheet, asset, dataStyle, currentRow);
                
                // Add bottom border to last row of this asset
                addBottomBorderToLastRow(sheet, bottomBorderStyle, currentRow - 1);
            }
            
            // Auto-size columns
            autoSizeColumns(sheet);
            
            // Convert to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }
    
    private int processAssetSimplified(Sheet sheet, JsonNode asset, CellStyle dataStyle, int currentRow) {
        // Calculate how many rows this asset needs
        int licenseCount = getArraySize(asset, "licenseList");
        int castCount = getArraySize(asset, "cast");
        int crewCount = getArraySize(asset, "crew");
        int externalCount = getArraySize(asset, "external");
        
        int maxRows = Math.max(1, Math.max(licenseCount, Math.max(castCount, Math.max(crewCount, externalCount))));
        
        // Create rows for this asset
        for (int i = 0; i < maxRows; i++) {
            Map<String, String> rowData = buildCompleteRowData(asset, i);
            createExcelRow(sheet, rowData, dataStyle, currentRow++);
        }
        
        return currentRow;
    }
    
    private Map<String, String> buildCompleteRowData(JsonNode asset, int arrayIndex) {
        Map<String, String> rowData = new HashMap<>();
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        
        // Process each field using the mapping configuration
        for (String field : fields) {
            String value = getFieldValue(asset, field, arrayIndex);
            rowData.put(field, value);
        }
        
        return rowData;
    }
    
    private String getFieldValue(JsonNode asset, String field, int arrayIndex) {
        // Handle array-based fields
        if (isArrayField(field)) {
            return getArrayFieldValue(asset, field, arrayIndex);
        }
        
        // Handle simple asset fields
        return getSimpleFieldValue(asset, field);
    }
    
    private boolean isArrayField(String field) {
        // License fields
        if (field.equals("licenseId") || field.equals("start") || field.equals("end")) {
            return true;
        }
        // Cast/Crew fields
        if (field.equals("personName") || field.equals("role") || field.equals("characterName")) {
            return true;
        }
        // External fields
        if (field.equals("externalId") || field.equals("externalProvider") || field.equals("org")) {
            return true;
        }
        return false;
    }
    
    private String getArrayFieldValue(JsonNode asset, String field, int arrayIndex) {
        // License fields
        if (field.equals("licenseId") || field.equals("start") || field.equals("end")) {
            return getLicenseFieldValue(asset, field, arrayIndex);
        }
        
        // Cast/Crew fields - try cast first, then crew
        if (field.equals("personName") || field.equals("role") || field.equals("characterName")) {
            String castValue = getCastFieldValue(asset, field, arrayIndex);
            if (!castValue.isEmpty()) {
                return castValue;
            }
            return getCrewFieldValue(asset, field, arrayIndex);
        }
        
        // External fields
        if (field.equals("externalId") || field.equals("externalProvider") || field.equals("org")) {
            return getExternalFieldValue(asset, field, arrayIndex);
        }
        
        return "";
    }
    
    private String getLicenseFieldValue(JsonNode asset, String field, int arrayIndex) {
        JsonNode licenseList = asset.get("licenseList");
        if (licenseList != null && licenseList.isArray() && arrayIndex < licenseList.size()) {
            JsonNode license = licenseList.get(arrayIndex);
            String value = getTextValue(license, field);
            return formatDateIfNeeded(value, field);
        }
        return "";
    }
    
    private String getCastFieldValue(JsonNode asset, String field, int arrayIndex) {
        JsonNode cast = asset.get("cast");
        if (cast != null && cast.isArray() && arrayIndex < cast.size()) {
            JsonNode person = cast.get(arrayIndex);
            return getTextValue(person, field);
        }
        return "";
    }
    
    private String getCrewFieldValue(JsonNode asset, String field, int arrayIndex) {
        JsonNode crew = asset.get("crew");
        if (crew != null && crew.isArray()) {
            // Adjust index for crew (comes after cast)
            JsonNode cast = asset.get("cast");
            int castSize = (cast != null && cast.isArray()) ? cast.size() : 0;
            int crewIndex = arrayIndex - castSize;
            
            if (crewIndex >= 0 && crewIndex < crew.size()) {
                JsonNode person = crew.get(crewIndex);
                return getTextValue(person, field);
            }
        }
        return "";
    }
    
    private String getExternalFieldValue(JsonNode asset, String field, int arrayIndex) {
        JsonNode external = asset.get("external");
        if (external != null && external.isArray() && arrayIndex < external.size()) {
            JsonNode ext = external.get(arrayIndex);
            
            // Map field names
            String jsonField = field;
            if (field.equals("externalId")) jsonField = "id";
            if (field.equals("externalProvider")) jsonField = "provider";
            
            return getTextValue(ext, jsonField);
        }
        return "";
    }
    
    private String getSimpleFieldValue(JsonNode asset, String field) {
        // Handle array fields that should be comma-separated
        if (field.equals("regionList") || field.equals("tags") || field.equals("keywords")) {
            return getArrayAsString(asset, field);
        }
        
        // Handle regular fields
        String value = getTextValue(asset, field);
        return formatDateIfNeeded(value, field);
    }
    
    private String formatDateIfNeeded(String value, String field) {
        if (value != null && !value.isEmpty() && 
            (field.contains("Date") || field.equals("start") || field.equals("end")) && 
            value.contains("T")) {
            return value.replace("T", " ").replace("Z", "");
        }
        return value;
    }
    
    private void createExcelRow(Sheet sheet, Map<String, String> rowData, CellStyle dataStyle, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        
        for (int i = 0; i < fields.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellStyle(dataStyle);
            String value = rowData.getOrDefault(fields[i], "");
            cell.setCellValue(value);
        }
    }
    
    // Helper methods
    private int getArraySize(JsonNode node, String fieldName) {
        JsonNode array = node.get(fieldName);
        return (array != null && array.isArray()) ? array.size() : 0;
    }
    
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return (value != null && !value.isNull()) ? value.asText() : "";
    }
    
    private String getArrayAsString(JsonNode node, String fieldName) {
        JsonNode array = node.get(fieldName);
        if (array == null || !array.isArray()) return "";
        
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            items.add(item.asText());
        }
        return String.join(", ", items);
    }
    
    private int createGroupHeaders(Sheet sheet, CellStyle style, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        String[] groups = FieldMappingConfig.getGroupsInOrder();
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        
        int colIndex = 0;
        for (String group : groups) {
            int groupStartCol = colIndex;
            int groupColCount = 0;
            
            // Count columns for this group
            for (String field : fields) {
                if (group.equals(FieldMappingConfig.FIELD_TO_GROUP_MAP.get(field))) {
                    groupColCount++;
                }
            }
            
            if (groupColCount > 0) {
                Cell cell = row.createCell(groupStartCol);
                cell.setCellValue(group);
                cell.setCellStyle(style);
                
                // Merge cells for group header if more than one column
                if (groupColCount > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 
                        groupStartCol, groupStartCol + groupColCount - 1));
                }
                
                colIndex += groupColCount;
            }
        }
        
        return rowIndex + 1;
    }
    
    private int createColumnHeaders(Sheet sheet, CellStyle style, int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        
        for (int i = 0; i < fields.length; i++) {
            Cell cell = row.createCell(i);
            String columnName = FieldMappingConfig.FIELD_TO_COLUMN_MAP.get(fields[i]);
            cell.setCellValue(columnName != null ? columnName : fields[i]);
            cell.setCellStyle(style);
        }
        
        return rowIndex + 1;
    }
    
    private void addBottomBorderToLastRow(Sheet sheet, CellStyle borderStyle, int rowIndex) {
        Row lastRow = sheet.getRow(rowIndex);
        if (lastRow != null) {
            String[] fields = FieldMappingConfig.getFieldsInOrder();
            for (int i = 0; i < fields.length; i++) {
                Cell cell = lastRow.getCell(i);
                if (cell != null) {
                    cell.setCellStyle(borderStyle);
                }
            }
        }
    }
    
    private void autoSizeColumns(Sheet sheet) {
        String[] fields = FieldMappingConfig.getFieldsInOrder();
        for (int i = 0; i < fields.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }
    
    // Style creation methods
    private CellStyle createGroupHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
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
