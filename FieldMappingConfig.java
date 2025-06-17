import org.apache.poi.ss.usermodel.IndexedColors;
import java.util.*;

public class FieldMappingConfig {
    
    public enum FieldType {
        SIMPLE,           // Regular asset field: "id", "title"
        ARRAY,            // Array field: "licenseId" from "licenseList"
        COMMA_SEPARATED   // Array as CSV: "regionList" -> "US, UK, CA"
    }
    
    public static class FieldMetadata {
        public final String columnName;       // Excel column header
        public final String group;            // Group header (for grouping columns)
        public final FieldType type;          // How to process this field
        public final String sourceArray;      // For ARRAY type: array name in JSON
        public final String sourceField;      // For ARRAY type: field in array object
        
        public FieldMetadata(String columnName, String group, FieldType type, String sourceArray, String sourceField) {
            this.columnName = columnName;
            this.group = group;
            this.type = type;
            this.sourceArray = sourceArray;
            this.sourceField = sourceField;
        }
    }
    
    // The central source of truth - all field definitions
    public static final Map<String, FieldMetadata> FIELD_CONFIG = new LinkedHashMap<>();
    
    // Group colors for styling
    public static final Map<String, IndexedColors> GROUP_COLORS = new HashMap<>();
    
    static {
        // Define field configurations
        // Asset Details
        FIELD_CONFIG.put("id", new FieldMetadata("Asset ID", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("title", new FieldMetadata("Title", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("type", new FieldMetadata("Type", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("language", new FieldMetadata("Language", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("duration", new FieldMetadata("Duration", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("releaseDate", new FieldMetadata("Release Date", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("description", new FieldMetadata("Description", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("genre", new FieldMetadata("Genre", "Asset Details", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("rating", new FieldMetadata("Rating", "Asset Details", FieldType.SIMPLE, null, null));
        
        // Content Info
        FIELD_CONFIG.put("tags", new FieldMetadata("Tags", "Content Info", FieldType.COMMA_SEPARATED, null, null));
        FIELD_CONFIG.put("keywords", new FieldMetadata("Keywords", "Content Info", FieldType.COMMA_SEPARATED, null, null));
        FIELD_CONFIG.put("contentAdvisory", new FieldMetadata("Content Advisory", "Content Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("availability", new FieldMetadata("Availability", "Content Info", FieldType.SIMPLE, null, null));
        
        // Technical Info
        FIELD_CONFIG.put("deliveryFormat", new FieldMetadata("Delivery Format", "Technical Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("audioLanguages", new FieldMetadata("Audio Languages", "Technical Info", FieldType.COMMA_SEPARATED, null, null));
        FIELD_CONFIG.put("subtitleLanguages", new FieldMetadata("Subtitle Languages", "Technical Info", FieldType.COMMA_SEPARATED, null, null));
        
        // Media Files
        FIELD_CONFIG.put("poster", new FieldMetadata("Poster", "Media Files", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("trailer", new FieldMetadata("Trailer", "Media Files", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("thumbnails", new FieldMetadata("Thumbnails", "Media Files", FieldType.COMMA_SEPARATED, null, null));
        
        // Cast & Crew - Array fields
        FIELD_CONFIG.put("personName", new FieldMetadata("Person Name", "Cast & Crew", FieldType.ARRAY, "cast", "personName"));
        FIELD_CONFIG.put("role", new FieldMetadata("Role", "Cast & Crew", FieldType.ARRAY, "cast", "role"));
        FIELD_CONFIG.put("characterName", new FieldMetadata("Character Name", "Cast & Crew", FieldType.ARRAY, "cast", "characterName"));
        
        // License Details - Array fields
        FIELD_CONFIG.put("licenseId", new FieldMetadata("License ID", "License Details", FieldType.ARRAY, "licenseList", "licenseId"));
        FIELD_CONFIG.put("start", new FieldMetadata("License Start Date", "License Details", FieldType.ARRAY, "licenseList", "start"));
        FIELD_CONFIG.put("end", new FieldMetadata("License End Date", "License Details", FieldType.ARRAY, "licenseList", "end"));
        
        // External Systems - Array fields
        FIELD_CONFIG.put("externalId", new FieldMetadata("External ID", "External Systems", FieldType.ARRAY, "external", "id"));
        FIELD_CONFIG.put("externalProvider", new FieldMetadata("External Provider", "External Systems", FieldType.ARRAY, "external", "provider"));
        FIELD_CONFIG.put("org", new FieldMetadata("Organization", "External Systems", FieldType.ARRAY, "external", "org"));
        
        // Distribution Info
        FIELD_CONFIG.put("regionList", new FieldMetadata("Regions", "Distribution Info", FieldType.COMMA_SEPARATED, null, null));
        FIELD_CONFIG.put("distributionWindow", new FieldMetadata("Distribution Window", "Distribution Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("platforms", new FieldMetadata("Platforms", "Distribution Info", FieldType.COMMA_SEPARATED, null, null));
        FIELD_CONFIG.put("scheduling", new FieldMetadata("Scheduling", "Distribution Info", FieldType.SIMPLE, null, null));
        
        // Source Info
        FIELD_CONFIG.put("provider", new FieldMetadata("Provider", "Source Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("ingestionDate", new FieldMetadata("Ingestion Date", "Source Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("sourceSystem", new FieldMetadata("Source System", "Source Info", FieldType.SIMPLE, null, null));
        
        // Quality Control
        FIELD_CONFIG.put("qcStatus", new FieldMetadata("QC Status", "Quality Control", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("qcComments", new FieldMetadata("QC Comments", "Quality Control", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("approvalStatus", new FieldMetadata("Approval Status", "Quality Control", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("reviewer", new FieldMetadata("Reviewer", "Quality Control", FieldType.SIMPLE, null, null));
        
        // Asset Management
        FIELD_CONFIG.put("version", new FieldMetadata("Version", "Asset Management", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("parentAsset", new FieldMetadata("Parent Asset", "Asset Management", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("childAssets", new FieldMetadata("Child Assets", "Asset Management", FieldType.COMMA_SEPARATED, null, null));
        
        // Additional Info
        FIELD_CONFIG.put("notes", new FieldMetadata("Notes", "Additional Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("createdBy", new FieldMetadata("Created By", "Additional Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("createdDate", new FieldMetadata("Created Date", "Additional Info", FieldType.SIMPLE, null, null));
        FIELD_CONFIG.put("updatedDate", new FieldMetadata("Updated Date", "Additional Info", FieldType.SIMPLE, null, null));
        
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
    
    public static int countColumnsInGroup(String[] fields, String group) {
        int count = 0;
        for (String field : fields) {
            FieldMetadata metadata = FIELD_CONFIG.get(field);
            if (metadata != null && group.equals(metadata.group)) {
                count++;
            }
        }
        return count;
    }
} 