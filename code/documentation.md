# Excel Export Service - Developer Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture & Design Principles](#architecture--design-principles)
3. [Core Components](#core-components)
4. [Field Mapping System](#field-mapping-system)
5. [Processing Flow](#processing-flow)
6. [Function Reference](#function-reference)
7. [How to Add New Fields](#how-to-add-new-fields)
8. [How to Modify Existing Fields](#how-to-modify-existing-fields)
9. [Troubleshooting](#troubleshooting)
10. [Best Practices](#best-practices)

---

## Overview

The Excel Export Service is a Spring Boot component designed to generate Excel files from JSON asset data. It follows SOLID principles and implements a configuration-driven approach that makes adding new fields or modifying existing ones extremely simple.

### Key Features
- **Configuration-driven**: All field definitions in one place
- **Type-safe processing**: Different field types handled appropriately
- **Visual appeal**: Group-specific colors for better readability
- **Maintainable**: Adding new fields requires minimal code changes
- **Flexible**: Supports simple fields, arrays, and comma-separated values

---

## Architecture & Design Principles

### Design Principles Applied
- **SOLID Principles**: Clean separation of concerns, single responsibility
- **KISS (Keep It Simple, Stupid)**: Simple, understandable logic
- **DRY (Don't Repeat Yourself)**: Unified processing approach
- **Single Source of Truth**: All field configurations centralized

### Architecture Pattern
```
┌─────────────────────────────────────────────────────────────┐
│                    ExcelExportController                    │
│                    (REST Endpoint)                         │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                 ExcelExportService                         │
│              (Main Processing Logic)                       │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                FieldMappingConfig                          │
│            (Configuration & Metadata)                      │
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. FieldMappingConfig.java
**Purpose**: Central configuration class that defines all field metadata and processing rules.

**Key Elements**:
- `FieldType` enum: Defines how fields should be processed
- `FieldMetadata` class: Contains complete field information
- `FIELD_CONFIG` map: Single source of truth for all field definitions
- `GROUP_COLORS` map: Defines colors for each group header

### 2. ExcelExportService.java
**Purpose**: Main service that handles Excel generation and data processing.

**Key Responsibilities**:
- Fetches data from API (or mock JSON)
- Processes assets based on field configurations
- Creates Excel workbook with styled headers and data
- Handles different field types uniformly

### 3. ExcelExportController.java
**Purpose**: REST controller that exposes the Excel export endpoint.

**Endpoint**: `GET /export/excel`
**Response**: Excel file download with timestamped filename

---

## Field Mapping System

### FieldType Enum
Defines three types of field processing:

```java
public enum FieldType {
    SIMPLE,           // Regular asset field: "id", "title"
    ARRAY,            // Array field: "licenseId" from "licenseList"
    COMMA_SEPARATED   // Array as CSV: "regionList" -> "US, UK, CA"
}
```

### FieldMetadata Class
Contains complete information about each field:

```java
public static class FieldMetadata {
    public final String columnName;       // Excel column header
    public final String group;            // Group header (for grouping columns)
    public final FieldType type;          // How to process this field
    public final String sourceArray;      // For ARRAY type: array name in JSON
    public final String sourceField;      // For ARRAY type: field in array object
}
```

### Field Configuration Examples

#### Simple Field
```java
FIELD_CONFIG.put("title", new FieldMetadata("Title", "Asset Details", FieldType.SIMPLE, null, null));
```
- Reads directly from asset JSON: `asset.title`
- Appears only in first row of each asset

#### Array Field
```java
FIELD_CONFIG.put("licenseId", new FieldMetadata("License ID", "License Details", FieldType.ARRAY, "licenseList", "licenseId"));
```
- Reads from: `asset.licenseList[index].licenseId`
- Creates multiple rows if array has multiple items

#### Comma-Separated Field
```java
FIELD_CONFIG.put("tags", new FieldMetadata("Tags", "Content Info", FieldType.COMMA_SEPARATED, null, null));
```
- Reads array from: `asset.tags`
- Converts to: "tag1, tag2, tag3"
- Appears only in first row

### Group Colors
Each group has a distinct color for visual appeal:

```java
GROUP_COLORS.put("Asset Details", IndexedColors.LIGHT_BLUE);
GROUP_COLORS.put("Content Info", IndexedColors.LIGHT_GREEN);
GROUP_COLORS.put("Technical Info", IndexedColors.LIGHT_YELLOW);
// ... more groups
```

---

## Processing Flow

### High-Level Flow
```
1. Fetch Assets from API/JSON
2. Create Excel Workbook
3. Create Group Headers (Row 1)
4. Create Column Headers (Row 2)
5. For Each Asset:
   a. Calculate max rows needed
   b. Process each row with field data
   c. Add bottom border to last row
6. Auto-size columns
7. Return Excel as byte array
```

### Detailed Asset Processing
```
For each asset:
1. calculateMaxRows() - Find largest array size
2. For each row index (0 to maxRows-1):
   a. buildRowData() - Extract values for all fields
   b. createExcelRow() - Create Excel row with data
3. addBottomBorderToRow() - Style last row
```

### Field Value Extraction Logic
```java
switch (metadata.type) {
    case SIMPLE:
        return arrayIndex == 0 ? getSimpleValue(asset, fieldName) : "";
    case ARRAY:
        return getArrayValue(asset, metadata.sourceArray, metadata.sourceField, arrayIndex);
    case COMMA_SEPARATED:
        return arrayIndex == 0 ? getCommaSeparatedValue(asset, fieldName) : "";
}
```

---

## Function Reference

### FieldMappingConfig Functions

#### `getFieldsInOrder()`
**Purpose**: Returns all field names in the order they should appear in Excel
**Returns**: `String[]` - Array of field names
**Usage**: Used to maintain consistent column ordering

#### `getGroupsInOrder()`
**Purpose**: Returns unique group names in order of appearance
**Returns**: `String[]` - Array of group names
**Usage**: Used for creating group headers

#### `countColumnsInGroup(String[] fields, String group)`
**Purpose**: Counts how many columns belong to a specific group
**Parameters**: 
- `fields`: Array of field names
- `group`: Group name to count
**Returns**: `int` - Number of columns in the group
**Usage**: Used for merging group header cells

### ExcelExportService Functions

#### Core Processing Functions

##### `generateExcel()`
**Purpose**: Main entry point for Excel generation
**Returns**: `byte[]` - Excel file as byte array
**Flow**: Orchestrates the entire Excel creation process

##### `processAsset(Sheet sheet, Workbook workbook, JsonNode asset, int currentRow)`
**Purpose**: Processes a single asset and creates all its rows
**Parameters**:
- `sheet`: Excel sheet to write to
- `workbook`: Excel workbook for styling
- `asset`: JSON data for the asset
- `currentRow`: Starting row index
**Returns**: `int` - Next available row index

##### `calculateMaxRows(JsonNode asset)`
**Purpose**: Determines how many rows an asset needs based on its largest array
**Logic**: Iterates through all ARRAY type fields and finds the maximum array size
**Returns**: `int` - Number of rows needed (minimum 1)

##### `buildRowData(JsonNode asset, int arrayIndex)`
**Purpose**: Extracts values for all fields for a specific row
**Parameters**:
- `asset`: JSON asset data
- `arrayIndex`: Which array index to process (0 for first row)
**Returns**: `Map<String, String>` - Field name to value mapping

#### Field Value Extraction Functions

##### `extractValueBasedOnType(JsonNode asset, String fieldName, FieldMetadata metadata, int arrayIndex)`
**Purpose**: Routes field extraction based on field type
**Logic**: Uses switch statement on `metadata.type`
**Returns**: `String` - Extracted field value

##### `getSimpleValue(JsonNode asset, String fieldName)`
**Purpose**: Extracts simple field values directly from asset
**Special Handling**: Formats dates by removing 'T' and 'Z'
**Returns**: `String` - Field value or empty string

##### `getArrayValue(JsonNode asset, String sourceArray, String sourceField, int arrayIndex)`
**Purpose**: Extracts value from array at specific index
**Logic**: `asset.sourceArray[arrayIndex].sourceField`
**Special Handling**: Formats license dates
**Returns**: `String` - Field value or empty string

##### `getCommaSeparatedValue(JsonNode asset, String fieldName)`
**Purpose**: Converts array to comma-separated string
**Logic**: Joins array elements with ", "
**Returns**: `String` - Comma-separated values

#### Excel Creation Functions

##### `createGroupHeaders(Sheet sheet, Workbook workbook, int rowIndex)`
**Purpose**: Creates colored group header row
**Logic**: 
1. Iterates through groups
2. Calculates column span for each group
3. Applies group-specific colors
4. Merges cells if group spans multiple columns

##### `createColumnHeaders(Sheet sheet, Workbook workbook, int rowIndex)`
**Purpose**: Creates column header row with field names
**Logic**: Uses `metadata.columnName` for each field

##### `createExcelRow(Sheet sheet, Map<String, String> rowData, CellStyle dataStyle, int rowIndex)`
**Purpose**: Creates a data row with values
**Logic**: Iterates through fields in order and sets cell values

#### Styling Functions

##### `createGroupHeaderStyle(Workbook workbook, String group)`
**Purpose**: Creates styled cell format for group headers
**Features**:
- Bold font, size 12
- Group-specific background color
- Centered alignment
- Borders on all sides

##### `createColumnHeaderStyle(Workbook workbook)`
**Purpose**: Creates styled cell format for column headers
**Features**:
- Bold font, size 10
- Grey background
- Centered alignment
- Borders on all sides

##### `createDataStyle(Workbook workbook)`
**Purpose**: Creates styled cell format for data cells
**Features**:
- Left alignment
- Top vertical alignment
- Borders on all sides

##### `createBottomBorderStyle(Workbook workbook)`
**Purpose**: Creates styled cell format for asset separator rows
**Features**:
- Same as data style but with thick bottom border
- Used to visually separate different assets

---

## How to Add New Fields

### Step 1: Identify Field Type
Determine which type your new field is:
- **SIMPLE**: Regular field directly on asset (e.g., `asset.newField`)
- **ARRAY**: Field from an array (e.g., `asset.someArray[i].newField`)
- **COMMA_SEPARATED**: Array that should be displayed as CSV

### Step 2: Add to Field Configuration
Add one line to `FieldMappingConfig.java` in the static block:

#### Example: Adding a Simple Field
```java
FIELD_CONFIG.put("budget", new FieldMetadata("Budget", "Asset Details", FieldType.SIMPLE, null, null));
```

#### Example: Adding an Array Field
```java
FIELD_CONFIG.put("directorName", new FieldMetadata("Director", "Cast & Crew", FieldType.ARRAY, "directors", "name"));
```

#### Example: Adding a Comma-Separated Field
```java
FIELD_CONFIG.put("categories", new FieldMetadata("Categories", "Content Info", FieldType.COMMA_SEPARATED, null, null));
```

### Step 3: Add Group Color (if new group)
If you're creating a new group, add a color:

```java
GROUP_COLORS.put("New Group", IndexedColors.LIGHT_CORAL);
```

### Step 4: Test
That's it! No other code changes needed. The system will automatically:
- Include the field in Excel export
- Apply appropriate processing based on type
- Use correct group color
- Handle the field in all rows

---

## How to Modify Existing Fields

### Change Column Name
```java
// Before
FIELD_CONFIG.put("title", new FieldMetadata("Title", "Asset Details", FieldType.SIMPLE, null, null));

// After
FIELD_CONFIG.put("title", new FieldMetadata("Asset Title", "Asset Details", FieldType.SIMPLE, null, null));
```

### Move Field to Different Group
```java
// Before
FIELD_CONFIG.put("rating", new FieldMetadata("Rating", "Asset Details", FieldType.SIMPLE, null, null));

// After
FIELD_CONFIG.put("rating", new FieldMetadata("Rating", "Content Info", FieldType.SIMPLE, null, null));
```

### Change Field Type
```java
// Before: Simple field
FIELD_CONFIG.put("genre", new FieldMetadata("Genre", "Asset Details", FieldType.SIMPLE, null, null));

// After: Comma-separated array
FIELD_CONFIG.put("genre", new FieldMetadata("Genres", "Asset Details", FieldType.COMMA_SEPARATED, null, null));
```

### Change Array Source
```java
// Before
FIELD_CONFIG.put("personName", new FieldMetadata("Person Name", "Cast & Crew", FieldType.ARRAY, "cast", "personName"));

// After: Include crew as well by changing source processing logic
// Note: This might require custom logic if you want to combine cast and crew
```

### Remove a Field
Simply delete or comment out the line:
```java
// FIELD_CONFIG.put("oldField", new FieldMetadata("Old Field", "Some Group", FieldType.SIMPLE, null, null));
```

---

## Troubleshooting

### Common Issues

#### Field Not Appearing in Excel
**Cause**: Field not added to `FIELD_CONFIG`
**Solution**: Add field configuration in `FieldMappingConfig.java`

#### Empty Values for Array Fields
**Cause**: Incorrect `sourceArray` or `sourceField` names
**Solution**: Check JSON structure and verify array/field names match exactly

#### Group Header Not Colored
**Cause**: Group name not in `GROUP_COLORS` map
**Solution**: Add group color mapping or check for typos in group name

#### Compilation Errors After Changes
**Cause**: Syntax errors in field configuration
**Solution**: Check for missing commas, quotes, or parentheses

#### Excel File Corrupted
**Cause**: Exception during Excel generation
**Solution**: Check logs for stack traces, verify JSON data structure

### Debugging Tips

1. **Enable Debug Logging**: Add logging to see which fields are being processed
2. **Check JSON Structure**: Verify your JSON matches expected field paths
3. **Test with Minimal Data**: Use small dataset to isolate issues
4. **Validate Field Names**: Ensure field names in config match JSON exactly

---

## Best Practices

### Field Configuration
1. **Use Descriptive Column Names**: Make headers user-friendly
2. **Group Related Fields**: Keep similar fields in same group
3. **Consistent Naming**: Use camelCase for field keys
4. **Logical Ordering**: Order fields as they should appear in Excel

### Code Maintenance
1. **Document New Groups**: Add comments for new groups explaining their purpose
2. **Test After Changes**: Always test Excel generation after modifications
3. **Keep Groups Balanced**: Don't create groups with too many or too few fields
4. **Use Meaningful Colors**: Choose colors that make sense for the group content

### Performance Considerations
1. **Limit Array Sizes**: Very large arrays can create huge Excel files
2. **Consider Field Count**: Too many fields can make Excel unwieldy
3. **Optimize for Common Use Cases**: Put most important fields first

### Data Handling
1. **Handle Null Values**: The system handles nulls gracefully, but be aware
2. **Date Formatting**: Dates are automatically formatted (T and Z removed)
3. **Array Bounds**: System handles missing array elements safely
4. **String Length**: Very long strings might affect Excel column width

---

## Example: Complete Field Addition Walkthrough

Let's say you want to add a new field for "Production Company" that comes from an array called `productionCompanies` in the JSON.

### Step 1: Analyze the JSON
```json
{
  "id": "asset123",
  "title": "Sample Movie",
  "productionCompanies": [
    {"name": "Studio A", "role": "Producer"},
    {"name": "Studio B", "role": "Co-Producer"}
  ]
}
```

### Step 2: Determine Field Type
Since we want to show each production company in separate rows, this is an **ARRAY** type field.

### Step 3: Add Configuration
```java
// Add this line in FieldMappingConfig.java static block
FIELD_CONFIG.put("productionCompanyName", new FieldMetadata("Production Company", "Production Info", FieldType.ARRAY, "productionCompanies", "name"));
FIELD_CONFIG.put("productionRole", new FieldMetadata("Production Role", "Production Info", FieldType.ARRAY, "productionCompanies", "role"));
```

### Step 4: Add Group Color (if new group)
```java
GROUP_COLORS.put("Production Info", IndexedColors.LIGHT_TURQUOISE);
```

### Step 5: Result
The Excel will now include:
- "Production Company" and "Production Role" columns
- Multiple rows per asset if multiple production companies exist
- "Production Info" group header with light turquoise color
- Proper alignment with other array fields

That's it! No other code changes needed.

---

## Conclusion

This Excel Export Service is designed for maximum maintainability and ease of use. The configuration-driven approach means that 99% of changes can be made by simply modifying the `FIELD_CONFIG` map. The system handles all the complex logic of array processing, styling, and Excel generation automatically.

For any questions or issues not covered in this documentation, refer to the code comments or contact the development team. 
