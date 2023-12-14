package com.dataphion.hermes.icebergIngest;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.blob.models.BlobItem;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.types.Type.PrimitiveType;
import org.apache.iceberg.types.Type.NestedType;
import org.apache.iceberg.types.Types.StructType;
import java.math.BigDecimal;


public class App {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        String azureAccountKey = System.getenv("AZURE_ACCOUNT_KEY");
        String azureAccountName = System.getenv("AZURE_ACCOUNT_NAME");
        String azureContainerName = System.getenv("AZURE_CONTAINER_NAME");

        String jdbcUrl = System.getenv("JDBC_URL");
        String jdbcUser = System.getenv("JDBC_USER");
        String jdbcPassword = System.getenv("JDBC_PASSWORD");

        String catalogName = System.getenv("ICEBERG_CATALOG_NAME");
        String namespace = System.getenv("ICEBERG_NAMESPACE");
        String tableName = System.getenv("ICEBERG_TABLE_NAME");
        String componentID = System.getenv("COMPONENT_ID");

        Configuration hadoopConf = new Configuration();
        hadoopConf.set("fs.azure.account.key." + azureAccountName + ".dfs.core.windows.net", azureAccountKey);
        

        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, "abfss://" + azureContainerName + "@" + azureAccountName + ".dfs.core.windows.net/warehouse/hermesdemo");
        properties.put(CatalogProperties.CATALOG_IMPL, "org.apache.iceberg.jdbc.JdbcCatalog");
        properties.put("uri", jdbcUrl);
        properties.put("jdbc.user", jdbcUser);
        properties.put("jdbc.password", jdbcPassword);

        JdbcCatalog catalog = new JdbcCatalog();
        Configuration conf = new Configuration();
        conf.addResource(hadoopConf);
        catalog.setConf(conf);
        catalog.initialize(catalogName, properties);

        TableIdentifier tableIdentifier = TableIdentifier.of(namespace, tableName);
        org.apache.iceberg.Table table = catalog.loadTable(tableIdentifier);
        Schema schema = table.schema();

        List<GenericRecord> records = readFromAzureBlob(azureContainerName, azureAccountName, componentID, schema, azureAccountKey);

        String filepath = table.location() + "/" + UUID.randomUUID().toString();
        OutputFile file = table.io().newOutputFile(filepath);
        DataWriter<GenericRecord> dataWriter;
		try {
			dataWriter = Parquet.writeData(file)
			        .schema(schema)
			        .createWriterFunc(GenericParquetWriter::buildWriter)
			        .overwrite()
			        .withSpec(PartitionSpec.unpartitioned())
			        .build();
			for (GenericRecord record : records) {
	            dataWriter.write(record);      
	        }
            dataWriter.close();
            DataFile dataFile = dataWriter.toDataFile();
			table.newAppend().appendFile(dataFile).commit();
            System.out.println("Record written to Iceberg table");
	        
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}   
    }

    public static List<GenericRecord> readFromAzureBlob(String containerName, String accountName, String componentID, Schema schema, String accountKey ) {
        List<GenericRecord> records = new ArrayList<>();
        StorageSharedKeyCredential credential = new StorageSharedKeyCredential(accountName, accountKey);
        try {
            BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                    .endpoint("https://" + accountName + ".blob.core.windows.net")
                    .credential(credential);

            BlobContainerClient containerClient = builder.buildClient().getBlobContainerClient(containerName);

            for (BlobItem blobItem : containerClient.listBlobs()) {
                String blobName = blobItem.getName();

                // Apply your pattern matching logic here
                if (blobName.startsWith("events/" + componentID + "/")) {
                    BlobClient blobClient = containerClient.getBlobClient(blobName);

                    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                        blobClient.download(outputStream);
                        String jsonContent = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
                        GenericRecord record = parseJsonToRecord(jsonContent, schema);
                        if (record != null) {
                            records.add(record);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return records;
    }

    private static GenericRecord parseJsonToRecord(String jsonLine, Schema schema) {
        try {
            JsonNode jsonNode = objectMapper.readTree(jsonLine);

            // Create a record using the Iceberg schema
            GenericRecord record = GenericRecord.create(schema);

            // Populate the record fields based on the JSON data
            for (Types.NestedField field : schema.asStruct().fields()) {
                String fieldName = field.name();

                // Assuming that the JSON field names match the Iceberg schema field names
                if (jsonNode.has(fieldName)) {
                    // Set the value directly without explicit conversion
                    Object fieldValue = extractJsonValue(jsonNode.get(fieldName), field.type());
                    record.setField(fieldName, fieldValue);
                }
            }

            return record;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Object extractJsonValue(JsonNode jsonNode, Type type) {
        switch (type.typeId()) {
            case STRING:
                return jsonNode.asText();
            case INTEGER:
                return jsonNode.asInt();
            case LONG:
                return jsonNode.asLong();
            case FLOAT:
                return (float) jsonNode.asDouble();
            case DOUBLE:
                return jsonNode.asDouble();
            case BOOLEAN:
                return jsonNode.asBoolean();
            case DECIMAL:
                return new BigDecimal(jsonNode.asText());
            case DATE:
                return java.sql.Date.valueOf(jsonNode.asText());
            case TIME:
                return java.sql.Time.valueOf(jsonNode.asText());
            case TIMESTAMP:
                return java.sql.Timestamp.valueOf(jsonNode.asText());
            case STRUCT:
                return extractStruct(jsonNode, (StructType) type);
            case LIST:
                return extractList(jsonNode, (Types.ListType) type);
            // Add cases for other types as needed
            default:
                throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    private static Record extractStruct(JsonNode jsonNode, StructType structType) {
        Record structRecord = GenericRecord.create(structType);
        for (Types.NestedField field : structType.fields()) {
            String fieldName = field.name();
            if (jsonNode.has(fieldName)) {
                Object fieldValue = extractJsonValue(jsonNode.get(fieldName), field.type());
                structRecord.setField(fieldName, fieldValue);
            }
        }
        return structRecord;
    }

    private static List<?> extractList(JsonNode jsonNode, Types.ListType listType) {
        List<Object> list = new ArrayList<>();
        Type elementType = listType.elementType();

        jsonNode.elements().forEachRemaining(element -> {
            Object elementValue = extractJsonValue(element, elementType);
            list.add(elementValue);
        });

        return list;
    }

    
}
