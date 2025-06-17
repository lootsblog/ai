# Excel Export Service - Implementation Plan

## Overview

This document outlines the design and implementation plan for the Excel Export Service, a component designed to generate Excel files from structured JSON data with a focus on maintainability, flexibility, and adherence to SOLID and KISS principles.

## Design Goals

- **Maintainable**: Adding new fields requires minimal code changes
- **Configurable**: Field definitions in a single source of truth
- **Flexible**: Support different field types (simple, array-based, comma-separated)
- **SOLID Compliant**: Follow good OO design principles
- **KISS**: Keep implementation simple and understandable

## Key Components

### 1. FieldMappingConfig (Source of Truth)

A central configuration class that defines all fields, their properties, and how they should be processed.

```java
public class FieldMappingConfig {
    
    public enum FieldType {
        SIMPLE,           // Regular asset field: "id", "title"
        ARRAY,            // Array field: "licenseId" from "licenseList"
        COMMA_SEPARATED   // Array as CSV: "regionList" -> "US, UK, CA"
    }
    
    public static class FieldMetadata {
        String columnName;       // Excel column header
        String group;            // Group header (for grouping columns)
        FieldType type;          // How to process this field
        String sourceArray;      // For ARRAY type: array name in JSON
        String sourceField;      // For ARRAY type: field in array object
    }
    
    // The central source of truth - all field definitions
    public static final Map<String, FieldMetadata> FIELD_CONFIG = new LinkedHashMap<>();
    
    // Group colors for styling
    public static final Map<String, IndexedColors> GROUP_COLORS = new HashMap<>();
    
    static {
        // Define field configurations
        FIELD_CONFIG.put("id", new FieldMetadata("Asset ID", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("title", new FieldMetadata("Title", "Asset Details", FieldType.SIMPLE, null, null));
        // ... other fields
        
        // Array fields
        FIELD_CONFIG.put("licenseId", new FieldMetadata("License ID", "License Details", FieldType.ARRAY, "licenseList", "licenseId"));
        FIELD_CONFIG.put("start", new FieldMetadata("License Start Date", "License Details", FieldType.ARRAY, "licenseList", "start"));
        FIELD_CONFIG.put("end", new FieldMetadata("License End Date", "License Details", FieldType.ARRAY, "licenseList", "end"));
        
        // Cast/Crew fields
        FIELD_CONFIG.put("personName", new FieldMetadata("Person Name", "Cast & Crew", FieldType.ARRAY, "cast", "personName"));
        FIELD_CONFIG.put("role", new FieldMetadata("Role", "Cast & Crew", FieldType.ARRAY, "cast", "role"));
        FIELD_CONFIG.put("characterName", new FieldMetadata("Character Name", "Cast & Crew", FieldType.ARRAY, "cast", "characterName"));
        
        // Comma-separated arrays
        FIELD_CONFIG.put("regionList", new FieldMetadata("Regions", "Distribution Info", FieldType.COMMA_SEPARATED, null, null));
        FIELD_CONFIG.put("tags", new FieldMetadata("Tags", "Content Info", FieldType.COMMA_SEPARATED, null, null));
        
        // Define group colors
        GROUP_COLORS.put("Asset Details", IndexedColors.LIGHT_BLUE);
        GROUP_COLORS.put("Content Info", IndexedColors.LIGHT_GREEN);
        GROUP_COLORS.put("Technical Info", IndexedColors.LIGHT_YELLOW);
        GROUP_COLORS.put("Media Files", IndexedColors.LIGHT_ORANGE);
        GROUP_COLORS.put("Cast & Crew", IndexedColors.LIGHT_CORNFLOWER_BLUE);
        GROUP_COLORS.put("License Details", IndexedColors.LIGHT_TURQUOISE);
        GROUP_COLORS.put("External Systems", IndexedColors.ROSE);
        GROUP_COLORS.put("Distribution Info", IndexedColors.LAVENDER);
        GROUP_COLORS.put("Source Info", IndexedColors.PALE_BLUE);
        GROUP_COLORS.put("Quality Control", IndexedColors.LIGHT_GREEN);
        GROUP_COLORS.put("Asset Management", IndexedColors.TAN);
        GROUP_COLORS.put("Additional Info", IndexedColors.GREY_25_PERCENT);
    }
    
    // Helper methods
    public static String[] getFieldsInOrder() {
        return FIELD_CONFIG.keySet().toArray(new String[0]);
    }
    
    public static String[] getGroupsInOrder() {
        Set<String> groups = new LinkedHashSet<>();
        for (FieldMetadata metadata : FIELD_CONFIG.values()) {
            groups.add(metadata.group);
        }
        return groups.toArray(new String[0]);
    }
}
```

### 2. ExcelExportService

The main service responsible for generating Excel files from JSON data.

```java
@Service
public class ExcelExportService {
    
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
    
    private Map<String, String> buildRowData(JsonNode asset, int arrayIndex) {
        Map<String, String> rowData = new HashMap<>();
        
        // Process each field according to its configuration
        for (Map.Entry<String, FieldMetadata> entry : FieldMappingConfig.FIELD_CONFIG.entrySet()) {
            String fieldName = entry.getKey();
            FieldMetadata metadata = entry.getValue();
            
            String value = extractValueBasedOnType(asset, fieldName, metadata, arrayIndex);
            rowData.put(fieldName, value);
        }
        
        return rowData;
    }
    
    private String extractValueBasedOnType(JsonNode asset, String fieldName, FieldMetadata metadata, int arrayIndex) {
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
    
    // Helper methods for different field types...
    
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
            int groupColCount = countColumnsInGroup(fields, group);
            
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
    
    // Other style and helper methods...
}
```

## Implementation Plan

1. **Phase 1: Core Configuration**
   - Create `FieldMetadata` class
   - Define `FieldType` enum
   - Set up `FIELD_CONFIG` with all field definitions
   - Define `GROUP_COLORS` for styling

2. **Phase 2: Excel Generation**
   - Implement field processing based on type
   - Create styled headers with group colors
   - Build row data extraction logic

3. **Phase 3: Testing & Optimization**
   - Verify all field types work correctly
   - Confirm array processing for multiple items
   - Test with various data scenarios

## Adding New Fields (Future Maintainability)

To add a new field to the Excel export:

1. **Add field configuration to `FIELD_CONFIG`**

```java
// Example: Adding a new simple field
FIELD_CONFIG.put("status", new FieldMetadata("Status", "Asset Details", FieldType.SIMPLE, null, null));

// Example: Adding a new array field
FIELD_CONFIG.put("creditName", new FieldMetadata("Credit Name", "Credits", FieldType.ARRAY, "credits", "name"));
```

2. **That's it!** No other code changes needed.

## Benefits of This Approach

- **Single Source of Truth**: All field definitions in one place
- **Minimal Code Changes**: Add new fields by just updating configuration
- **Type-Safe Processing**: Each field type has appropriate handling
- **Flexible**: Can handle various data structures
- **Visual Appeal**: Group-specific colors enhance readability
- **Maintainable**: Clean separation of configuration and processing logic

## Future Enhancements

- Configuration-driven date formatting
- Support for nested arrays
- Dynamic column width adjustment based on content
- Export templates for different use cases 