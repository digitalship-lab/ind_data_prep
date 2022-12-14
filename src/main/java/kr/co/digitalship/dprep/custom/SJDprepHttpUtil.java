package kr.co.digitalship.dprep.custom;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.LineNumberReader;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import javax.annotation.PostConstruct;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.http.client.utils.URIBuilder;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClientConfig.Builder;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.talend.dataprep.api.dataset.ColumnMetadata;
import org.talend.dataprep.api.dataset.DataSetMetadata;
import org.talend.dataprep.api.dataset.RowMetadata;
import org.talend.dataprep.api.dataset.statistics.SemanticDomain;
import org.talend.dataprep.api.preparation.Action;
import org.talend.dataprep.api.preparation.AppendStep;
import org.talend.dataprep.api.preparation.MixedContentMap;
import org.talend.dataprep.dataset.adapter.DatasetClient;
import org.talend.dataprep.dataset.store.metadata.DataSetMetadataRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.swagger.annotations.ApiParam;
import kr.co.digitalship.dprep.custom.schedule.util.DprepUtil;
import kr.co.digitalship.dprep.custom.schedule.util.HadoopUtil;

@Component
public class SJDprepHttpUtil {
	private static final Logger LOGGER = LoggerFactory.getLogger(SJDprepHttpUtil.class);

	@Value("${hadoop.read.base.path:}")
    private String hadoopReadBasePath;	
	
    @Value("${dataset.service.url:}")
    private String[] baseUrl; 
	
    @Value("${preparation.store.file.location:}")
    private String preparationsLocation;

    @Value("${dataset.metadata.store.file.location:}")
    private String datasetLocation;    
    
    @Value("${sejong.api.export.base.path:}")
    private String sejongApiExportBasePath;
    
    @Value("${sejong.api.temp.path:}")
    private String sejongApiTempPath;
    
	@Value("${dataprep.httpUtil.counter:0}")
	public static AtomicInteger counter; // ?????? ??? ????????? Http Connection ??? ?????? ?????? ????????? ??????...
	
	@Value("${pcn.api.enabled:false}")
	private boolean pcnApiEnabled; 	
	
    @Autowired
    protected ObjectMapper objectMapper;
    
	@Autowired
	private HadoopUtil hadoopUtil;
	
    @Autowired
    private DatasetClient datasetClient;
    
	@Autowired
	private PcnApiUtil pcnApiUtil;    
    
    @Autowired
    protected DataSetMetadataRepository dataSetMetadataRepository;    

	private final String CREATE_DATASET_PATH            = "/api/datasets";                 // POST
	private final String DATASET_METADATA_PATH          = "/api/datasets/%s/metadata";     // GET
	private final String CREATE_PREPARATION_PATH        = "/api/preparations";             // POST
	private final String ADD_ACTION_BASE_PATH           = "/preparations/%s/actions";      // POST	                 
	private final String CREATE_CACHE_BASE_PATH         = "/api/preparations/%s/content";  // GET
	private final String EXPORT_PATH                    = "/api/export";                   // GET
	private final String PREPARATION_BASE_INFO_PATH     = "/preparations/%s";              // GET
	private final String DELETE_PREPARATION_PATH        = "/api/preparations/%s";          // DELETE
	private final String DELETE_DATASET_PATH            = "/api/datasets/%s";              // DELETE
	
	private Builder clientBuilder;
	
	public String getCreateDatasetPath() {
		return CREATE_DATASET_PATH;
	}
	
	public String getDatasetMetadataPath(String datasetId) {
		return String.format(DATASET_METADATA_PATH, datasetId);
	}	
	
	public String getCreatePreparationPath() {
		return CREATE_PREPARATION_PATH;
	}
	
	public String getAddActionPath(String preparationId) {
		return String.format(ADD_ACTION_BASE_PATH, preparationId);
	}	

	public String getCreateCachePath(String preparationId) {
		return String.format(CREATE_CACHE_BASE_PATH, preparationId);
	}	
	
	public String getExportPath() {
		return EXPORT_PATH;
	}
	
	public String getPreparationBaseInfoPath(String preparationId) {
		return String.format(PREPARATION_BASE_INFO_PATH, preparationId);
	}
	
	public String getDeletePreparationPath(String preparationId) {
		return String.format(DELETE_PREPARATION_PATH, preparationId);
	}

	public String getDeleteDatasetPath(String datasetId) {
		return String.format(DELETE_DATASET_PATH, datasetId);
	}	
	
	private JsonObject datasetMetadata = null;
	
	public void setDatasetMetadata(JsonObject datasetMetadata) {
		this.datasetMetadata = datasetMetadata;
	}	
	
	@PostConstruct
	public SJDprepHttpUtil init() {
        this.clientBuilder = Dsl.config();
        this.clientBuilder.setRequestTimeout(600000); // default 60000
        this.clientBuilder.setReadTimeout(600000); // default 60000
        this.clientBuilder.setConnectTimeout(50000); // default 5000
        this.clientBuilder.setPooledConnectionIdleTimeout(600000); // default 60000

    	PropertiesUtil properties = Singleton.getInstance().getPropertiesUtil();
    	
    	hadoopReadBasePath = properties.getProperty("hadoop.read.base.path");
    	baseUrl = properties.getProperty("dataset.service.url").trim().split("\\s*,\\s*");
    	preparationsLocation = properties.getProperty("preparation.store.file.location");
    	datasetLocation = properties.getProperty("dataset.metadata.store.file.location");
    	sejongApiExportBasePath = properties.getProperty("sejong.api.export.base.path");
    	sejongApiTempPath = properties.getProperty("sejong.api.temp.path");
    	counter = new AtomicInteger(Integer.parseInt(properties.getProperty("dataprep.httpUtil.counter")));    	
    	pcnApiEnabled = new Boolean(properties.getProperty("pcn.api.enabled")).booleanValue();

        return this;
	}
	
	// ??????????????? ?????????
	public void createSampleDataFile(FileSystem fs, String wsId, int sampleDataLines) {
		File targetFile = null;
		String fileBasePath = sejongApiExportBasePath + "/" + wsId;
		//
		List<String> fileList = hadoopUtil.getFileList(fs, fileBasePath); // ????????? ?????? ????????? ????????? ??????

		if(null != fileList && 0 < fileList.size()) {
			boolean flag = true;
			
			for(int i = fileList.size() - 1; i >= 0; i--) {
				if(-1 < "sample.csv".indexOf(fileList.get(i))) {
					flag = false;
					break;
				}
			}
			
			if(flag) {
				for(int i = fileList.size() - 1; i >= 0; i--) {
					hadoopUtil.delete(fs, fileList.get(i));					
				}
			}
			fileList = null;
		}
		
		if(null == fileList || 0 == fileList.size()) {
			String strReadPath = hadoopReadBasePath + "/" + wsId;
			
            if(pcnApiEnabled) {
                String token = pcnApiUtil.getAuth();            	
                JsonObject workspace = pcnApiUtil.getWorkspace(token, Integer.parseInt(wsId));
				if(null == workspace.get("body")) {
					return;
				}
				
                hadoopReadBasePath = workspace.get("body").getAsJsonObject().get("filePath").getAsString();
                strReadPath = hadoopReadBasePath.substring(0, hadoopReadBasePath.length() - 1);
            }						
			
			fileList = hadoopUtil.getFileList(fs, strReadPath); // ?????? ?????? ????????? ?????? ?????? ??????

			Random random = new Random();
			InputStream inputStream = hadoopUtil.getInputStream(fs, fileList.get(random.nextInt(fileList.size())));
			//InputStream inputStream = FileUtils.openInputStream(new File("/Users/motive/Downloads/Source/kWeather_Data_Pcn/20200924.csv"));
			targetFile = new File(sejongApiTempPath + "/sample.tmp");
			
			StringBuffer stringBuffer;
			try {
				FileUtils.copyInputStreamToFile(inputStream, targetFile);
				
				LineNumberReader lineNumberReader = new LineNumberReader(new FileReader(targetFile));
				lineNumberReader.skip(Long.MAX_VALUE);			
				int totalLines = lineNumberReader.getLineNumber();
				
				lineNumberReader.close();
				
				int lineBaseInterval = totalLines / sampleDataLines;
				
				List<Integer> sampleDataLineNumList = new ArrayList<>();
				for(int i = 0, len = sampleDataLines; i < len; i++) {
					sampleDataLineNumList.add((i * lineBaseInterval) + random.nextInt(lineBaseInterval));				
				}
				
				stringBuffer = new StringBuffer();
				try(BufferedReader reader = new BufferedReader(new FileReader(targetFile))) {
					String line = null;
					int lineNo = 0;
					
					while(null != (line = reader.readLine())) {
						lineNo += 1;					
						if(sampleDataLineNumList.contains(lineNo)) {
							stringBuffer.append(line).append("\n");
						}
					}
				}
				hadoopUtil.write(fs, stringBuffer.toString(), fileBasePath + "/sample.csv");
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				if(null != targetFile) targetFile.delete();
			}			
		} 
	}
	
	// ???????????? ?????? ????????????
	public Deque<Map<String, String>> genCreateDatasetParams(Deque<String> fileNames, String tag, long size, Deque<String> datasetIds) {
		int len = datasetIds.size();
		Deque<Map<String, String>> params = new ConcurrentLinkedDeque<>();
		
		for(int i = 0; i < len; i++) {
			Map<String, String> param = new HashMap<>();
			
			param.put("path", this.getCreateDatasetPath());
			param.put("name", fileNames.poll());
			param.put("tag", tag);
			param.put("size", String.valueOf(size));
			param.put("generatedId", datasetIds.poll());
			
			params.add(param);
		}
		return params;
	}
	
	// dataset metadata
	public DataSetMetadata getDataSetMetadata(String datasetId) {
		return datasetClient.getDataSetMetadata(datasetId);
	}
	
	// ????????? ?????? ??????
    public DataSetMetadata changeDomainInfo(DataSetMetadata dataSetMetadata) {
		RowMetadata rowMetadata = dataSetMetadata.getRowMetadata();
		List<ColumnMetadata> listColumnMetadata = rowMetadata.getColumns();
		
		for(int i = 2, len = listColumnMetadata.size(); i < len; i++) {
			String changeDomain = null;
			if(2 == i) {
				changeDomain = "????????????";
			}
			else if(3 == i) {
				changeDomain = "???????????????";
			}
			else if(4 == i) {
				changeDomain = "??????";
			}	
			else if(5 == i) {
				changeDomain = "??????";
			}			
			
			ColumnMetadata columnMetadata = listColumnMetadata.get(i);
			List<SemanticDomain> listSemanticDomain = columnMetadata.getSemanticDomains();
			
			for(int j = 0, jlen = listSemanticDomain.size(); j < jlen; j++) {
				SemanticDomain semanticDomain = listSemanticDomain.get(j);
				
				if(changeDomain.equals(semanticDomain.getId())) {
					columnMetadata.setDomain(changeDomain);
					columnMetadata.setDomainLabel(changeDomain);
					columnMetadata.setDomainFrequency(semanticDomain.getScore());
					break;
				}
			}
		}
		
		dataSetMetadataRepository.save(dataSetMetadata);
		
		return dataSetMetadata;
    }
	
	// Preparation ?????? ????????????
	public Map<String, String> genCreatePreparationParam() {
		Map<String, String> param = new HashMap<>();
		
		param.put("path", this.getCreatePreparationPath());
		param.put("folder", "Lw==");
		
		return param;
	}
	
	// ?????? ???????????? 
	public Map<String, String> genExportParam() {
    	Map<String, String> param = new HashMap<>();
    	
    	String delimiter = this.datasetMetadata.get("parameters").getAsJsonObject().get("SEPARATOR").getAsString();
    	String encoding = this.datasetMetadata.get("encoding").getAsString();
    	
    	param.put("path", this.getExportPath());    	
		param.put("exportType", "CSV");
    	param.put("exportParameters.csv_fields_delimiter", delimiter);    	
		param.put("exportParameters.csv_enclosure_character", "");
		param.put("exportParameters.csv_escape_character", "");
		param.put("exportParameters.csv_enclosure_mode", "text_only");
		param.put("exportParameters.csv_encoding", encoding);
		
		return param;
	}
	
	public JsonObject getPreparationMetadata(String preparationId) {
		File file = new File(preparationsLocation + "/PersistentPreparation-" + preparationId);
		
		Map<String, String> param = new HashMap<>();
        param.put("path", this.getPreparationBaseInfoPath(preparationId));
        
        URIBuilder uriBuilders = this.uriBuild(param);
        String responseTxt = this.callHttp(uriBuilders, "GET", null);
        
        JsonObject persistantPreparationInfo = new JsonParser().parse(responseTxt).getAsJsonObject();
        String headId = persistantPreparationInfo.get("headId").getAsString();
        
        file = new File(preparationsLocation + "/PersistentStep-" + headId);
        String rowMetadataId = null;
        try (InputStream in = Files.newInputStream(Paths.get(file.getPath()), StandardOpenOption.READ); GZIPInputStream gZIPInputStream = new GZIPInputStream(in)) {
        	String persistentStep = IOUtils.toString(gZIPInputStream, "UTF-8");
        	JsonObject gsonObjectPersistentStep = new JsonParser().parse(persistentStep).getAsJsonObject();
        	
        	if(null != gsonObjectPersistentStep.get("rowMetadata")) {
            	rowMetadataId = gsonObjectPersistentStep.get("rowMetadata").getAsString();
        	}
        }
        catch (IOException e) {
        	e.printStackTrace();
        }        
		
        if(null != rowMetadataId) {
            file = new File(preparationsLocation + "/StepRowMetadata-" + rowMetadataId);
            try (InputStream in = Files.newInputStream(Paths.get(file.getPath()), StandardOpenOption.READ); GZIPInputStream gZIPInputStream = new GZIPInputStream(in)) {
            	String stepRowMetadata = IOUtils.toString(gZIPInputStream, "UTF-8");
            	return new JsonParser().parse(stepRowMetadata).getAsJsonObject();
            } 
            catch (IOException e) {
            	e.printStackTrace();
            }        	
        }
        return null;
	}
	
	// ?????? ????????? ???????????? ???????????? ??????("0"??? ?????? ????????? ?????????)
	public String getHeaderNbLines() {
		return this.datasetMetadata.get("parameters").getAsJsonObject().get("HEADER_NB_LINES").getAsString();
	}
	
	// URL + Param
	public URIBuilder uriBuild(Map<String, String> params) {
		URIBuilder uriBuilder = null;
		
		try {
			uriBuilder = new URIBuilder(this.baseUrl[0]);
			
			if(null != params) {
				Iterator<String> keys = params.keySet().iterator();
				while(keys.hasNext()) {
					String key = keys.next();
					
					if("path".equals(key)) {
						uriBuilder.setPath(params.get(key));
					}
					else {
						uriBuilder.addParameter(key, params.get(key));
					}
				}
			}
		} 
		catch (URISyntaxException e) {
			e.printStackTrace();
		}
		return uriBuilder;
	}

	// URL ?????? Sync
	public String callHttp(URIBuilder uriBuilder, String method, Object obj) {
        AsyncHttpClient asyncHttpClient = Dsl.asyncHttpClient(this.clientBuilder);
        
		BoundRequestBuilder boundRequestBuilder = null;
		
		if("POST".equals(method.toUpperCase())) {
			boundRequestBuilder = asyncHttpClient.preparePost(uriBuilder.toString());
		}
		else if("GET".equals(method.toUpperCase())) {
			boundRequestBuilder = asyncHttpClient.prepareGet(uriBuilder.toString());
		}
		else if("DELETE".equals(method.toUpperCase())) {
			boundRequestBuilder = asyncHttpClient.prepareDelete(uriBuilder.toString());
		}
		else if("PUT".equals(method.toUpperCase())) {
			boundRequestBuilder = asyncHttpClient.preparePut(uriBuilder.toString());
		}
		boundRequestBuilder = boundRequestBuilder.setCharset(Charset.forName("UTF-8"));
		
		String contentType = "text/plain";
        if(null != obj) {
        	if(obj instanceof JsonObject) {
        		contentType = "application/json";
        		boundRequestBuilder = boundRequestBuilder.setBody(((JsonObject)obj).toString());
        	}
        	// ???????????? ???????????? ??????
        	else if(obj instanceof ByteArrayInputStream) {
        		boundRequestBuilder = boundRequestBuilder.setBody(new InputStreamBodyGenerator((ByteArrayInputStream)obj));
        	}
        	// ?????? ??????
        	else if(obj instanceof JsonArray) {
        		contentType = "application/json";
        		boundRequestBuilder = boundRequestBuilder.setBody(((JsonArray)obj).toString());        		
        	}
        }		
		boundRequestBuilder = boundRequestBuilder.setHeader(CONTENT_TYPE, contentType);
		
		try {
			return this.getResponse(boundRequestBuilder);
		} 
		catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		} 
        finally {
        	try { if(null != asyncHttpClient) asyncHttpClient.close(); } catch (IOException e) {}
        }
		return null;
	}
	
	// ?????? ??????
	private String getResponse(BoundRequestBuilder boundRequestBuilder) throws InterruptedException, ExecutionException {
		ListenableFuture<Response> future = boundRequestBuilder.execute();
		Response response = future.get();
		
		if(null != response) {
			if(200 == response.getStatusCode()) {
				return response.getResponseBody();
			}
			// Export ??? ??????
			else if(202 == response.getStatusCode()) {
				return this.getResponse(boundRequestBuilder);
			}
		}
		return null;
	}
		
	// ????????? ?????? ????????????...
	public AppendStep createTestActionData() {
		AppendStep actionsContainer = new AppendStep();
		List<Action> actions = new ArrayList<>();
		Action action = new Action();
		action.setName("clear_invalid");
		
		MixedContentMap parameters = new MixedContentMap();
		parameters.put("column_id", "0003");
		
		action.setParameters(parameters);
		actions.add(action);
		
		action = new Action();
		action.setName("replace_on_value");
		
		parameters = new MixedContentMap();
		parameters.put("column_id", "0003");
		parameters.put("matching_value", "11");		
		parameters.put("operator", "contains");
		parameters.put("replace_value", "1111");
		
		action.setParameters(parameters);
		actions.add(action);
		
		action = new Action();
		action.setName("fillinvalidwithdefault");
		
		parameters = new MixedContentMap();
		parameters.put("column_id", "0004");
		parameters.put("replace_value", "0000");
		
		action.setParameters(parameters);
		actions.add(action);		
		
		actionsContainer.setActions(actions);
		
		return actionsContainer;
	}
	
	// ????????? ??????
	private JsonObject genActionDomainChange(String action, String columnId, String columnName, String newDomainId, String newDomainLabel, float newDomainFrequency) {
		JsonObject base = new JsonObject();
		base.addProperty("action", action);
		
		JsonObject sub = new JsonObject();
		sub.addProperty("scope", "column");
		sub.addProperty("column_id", columnId);
		sub.addProperty("column_name", columnName);
		sub.addProperty("new_domain_id", newDomainId);
		sub.addProperty("new_domain_label", newDomainLabel);
		sub.addProperty("new_domain_frequency", newDomainFrequency);
		
		base.add("parameters", sub);
		
		return base;
	}

	/**
	 * ????????? ?????????(clear_invalid) / ???????????? ?????? ??? ??????(delete_invalid) / ????????? ?????? ??? ??????(delete_empty)
	 * scope ??? column, line, cell ??? ??? ??? ????????? ???????????? action ??? column ?????? (???????????? line ??????, ?????? ???????????? ????????? ????????? cell ??????)
	 * ??????????????? column_name ??? ????????? ?????? ?????? ????????? ?????? ???????????? ???????????? ???????????? ?????? 
	 * column ???????????? row_id ??? ??? ????????? ??????????????? ??? ?????? ??????.
	 * 
	 * @param action : clear_invalid, delete_invalid, delete_empty
	 * @param columnId : eg) 0001
	 * @param rowId :eg) 1 - scope??? column?????? ?????? ??????.
	 * @return
	 */
	private JsonObject genActionDelete(String action, String columnId, String columnName) {
		JsonObject base = new JsonObject();
		base.addProperty("action", action);
		
		JsonObject sub = new JsonObject();
		sub.addProperty("scope", "column");
		sub.addProperty("column_id", columnId);
		sub.addProperty("column_name", columnName);
		sub.addProperty("row_id", "");
		
		base.add("parameters", sub);
		
		return base;
	}
	
	/**
	 * ????????? ?????????(fillinvalidwithdefault) / ?????? ?????????(fillemptywithdefault)
	 * scope ??? column, line, cell ??? ??? ??? ????????? ???????????? action ??? column ?????? (???????????? line ??????, ?????? ???????????? ????????? ????????? cell ??????)
	 * ??????????????? column_name ??? ????????? ?????? ????????? ?????? ??????...(??????) 
	 * column ???????????? row_id ??? ??? ????????? ??????????????? ??? ?????? ??????.
	 * 
	 * @param action : fillinvalidwithdefault, fillemptywithdefault
	 * @param defaultValue : ?????? ???
	 * @param columnId : eg) 0001
	 * @return
	 */	
	private JsonObject genActionFill(String action, String columnId, String columnName, String replacValue) {
		JsonObject base = new JsonObject();
		base.addProperty("action", action);
		
		JsonObject sub = new JsonObject();
		sub.addProperty("mode", "constant_mode"); // ?????? (???)
		sub.addProperty("default_value", replacValue);	// ?????? ???
		
		sub.addProperty("scope", "column");
		sub.addProperty("column_id", columnId);
		sub.addProperty("column_name", columnName);
		sub.addProperty("row_id", "");
		
		base.add("parameters", sub);
		
		return base;
	}
	
	/**
	 * ?????????(replace_on_value)
	 * scope ??? column, line, cell ??? ??? ??? ????????? ???????????? action ??? column ?????? (???????????? line ??????, ?????? ???????????? ????????? ????????? cell ??????)
	 * ??????????????? column_name ??? ????????? ?????? ????????? ?????? ??????...(??????) 
	 * column ???????????? row_id ??? ??? ????????? ??????????????? ??? ?????? ??????.
	 * 
	 * @param token : ????????? ?????? ???
	 * @param operator : equals, contains, starts_with, ends_with, regex
	 * @param replaceValue : ?????? ???
	 * @param columnId : eg) 0001
	 * @return
	 */
	private JsonObject genActionReplace(String action, String columnId, String columnName, String replaceValue, String matchingValue, String operator) {
		JsonObject base = new JsonObject();
		base.addProperty("action", action);
		
		JsonObject sub = new JsonObject();
		sub.addProperty("create_new_column", false);
		
		JsonObject sub_s2 = new JsonObject();
		sub_s2.addProperty("token", matchingValue); // ?????? ???
		sub_s2.addProperty("operator", operator); // equals, contains, starts_with, ends_with, regex
		
		sub.add("cell_value", sub_s2);		
		sub.addProperty("replace_value", replaceValue);
		sub.addProperty("replace_entire_cell", false); // ?????? ????????? ????????? ?????? ????????? ???????????? ?????? ?????? ?????? ????????? ????????? ??????
		
		sub.addProperty("scope", "column");
		sub.addProperty("column_id", columnId);
		sub.addProperty("column_name", columnName);
		sub.addProperty("row_id", "");
		
		base.add("parameters", sub);
		
		return base;
	}

	/**
	 * 
	 */
	public Action genDomainChangeAction(String columnId, String domainId) {
		Action action = new Action();
		action.setName("domain_change");
		
		MixedContentMap parameters = new MixedContentMap();
		parameters.put("scope", "column");
		parameters.put("column_id", columnId);		
		
		JsonArray columnNames = new JsonParser().parse(this.datasetMetadata.get("parameters").getAsJsonObject().get("COLUMN_HEADERS").getAsString()).getAsJsonArray();
		
		JsonArray columns = this.datasetMetadata.get("columns").getAsJsonArray();
		for(int i = 0, len = columns.size(); i < len; i++) {
			JsonObject column = columns.get(i).getAsJsonObject();
			
			if(columnId.equals(column.get("id").getAsString())) {
				JsonArray semanticDomains = column.get("semanticDomains").getAsJsonArray();
				
				for(int j = 0, jlen = semanticDomains.size(); j < jlen; j++) {
					JsonObject semanticDomain = semanticDomains.get(j).getAsJsonObject();
					
					if(domainId.equals(semanticDomain.get("id").getAsString())) {
						parameters.put("column_name", columnNames.get(j).getAsString());
						parameters.put("new_domain_id", domainId);
						parameters.put("new_domain_label", domainId);
						parameters.put("new_domain_frequency", semanticDomain.get("frequency").getAsString());
						
						action.setParameters(parameters);
						
						break;
					}
				}
				break;
			}
		}
		return action;
	}	
	
	/**
	 * Action - ?????? DPrep ?????? ????????? ?????? ?????????.
	 * ?????? ???????????? /api ??? ???????????? url ??? ???????????? ?????? /api ?????? ???????????? url ??? ??????. (????????? ????????? ?????? ??????)
	 *  
	 * @param 
	 * @return
	 */
	public JsonArray genActions(AppendStep actionsContainer) {
		// This trick is to keep the API taking and unrolling ONE AppendStep until the codefreeze but this must not stay that way
        try {
			List<AppendStep> stepsToAppend = actionsContainer.getActions().stream().map(a -> {
			    AppendStep s = new AppendStep();
			    s.setActions(singletonList(a));
			    return s;
			}).collect(toList());
			
			final String stepAsString = objectMapper.writeValueAsString(stepsToAppend);

			JsonElement gsonElement = new JsonParser().parse(stepAsString);
			JsonArray gsonArray = gsonElement.getAsJsonArray();

			JsonArray columnNames = new JsonParser().parse(this.datasetMetadata.get("parameters").getAsJsonObject().get("COLUMN_HEADERS").getAsString()).getAsJsonArray();
			JsonArray columns = this.datasetMetadata.get("columns").getAsJsonArray();
			
			for(int i = 0, len = gsonArray.size(); i < len; i++) {
				JsonArray actions = gsonArray.get(i).getAsJsonObject().get("actions").getAsJsonArray();
				
				for(int j = 0, jlen = actions.size(); j < jlen; j++) {
					JsonObject action = actions.get(j).getAsJsonObject();
					String actionName = action.get("action").getAsString();
					
					JsonObject parameters = action.get("parameters").getAsJsonObject();
					String columnId = parameters.get("column_id").getAsString();
					String columnName = null;
					
					for(int k = 0, klen = columns.size(); k < klen; k++) {
						JsonObject column = columns.get(k).getAsJsonObject();
						
						if(columnId.equals(column.get("id").getAsString())) {
							columnName = columnNames.get(k).getAsString();
							break;
						}
					}					
					
					JsonObject dprepAction = null;
					if("domain_change".equals(actionName)) {
						dprepAction = this.genActionDomainChange(actionName, columnId, columnName, parameters.get("new_domain_id").getAsString(), parameters.get("new_domain_label").getAsString(), parameters.get("new_domain_frequency").getAsFloat());
					}
					 // ????????? ?????????(clear_invalid) / ???????????? ?????? ??? ??????(delete_invalid) / ????????? ?????? ??? ??????(delete_empty)
					else if("clear_invalid".equals(actionName) || "delete_invalid".equals(actionName) || "delete_empty".equals(actionName)) {
						dprepAction = this.genActionDelete(actionName, columnId, columnName);
					}
					 // ????????? ?????????(fillinvalidwithdefault) / ?????? ?????????(fillemptywithdefault)					
					else if("fillinvalidwithdefault".equals(actionName) || "fillemptywithdefault".equals(actionName)) {
						dprepAction = this.genActionFill(actionName, columnId, columnName, parameters.get("replace_value").getAsString());
					}
					 // ?????????(replace_on_value)					
					else if("replace_on_value".equals(actionName)) {
						dprepAction = this.genActionReplace(actionName, columnId, columnName, parameters.get("replace_value").getAsString(), parameters.get("matching_value").getAsString(), parameters.get("operator").getAsString());
					}
					actions.set(j, dprepAction);
				}
			}
			return gsonArray;
		} 
        catch (JsonProcessingException e) {
			e.printStackTrace();
		}
        
		return null;
	}
	
	// Preparation ??????
	public void deletePreparation() {
		File dir = new File(preparationsLocation);
		File files[] = dir.listFiles();

		Map<String, String> param = new HashMap<>();
		for (int i = 0; i < files.length; i++) {
			if(-1 < files[i].getName().indexOf("sjpr")) {
				String preparationId = files[i].getName().replace("PersistentPreparation-", "");
							
				param.put("path", this.getPreparationBaseInfoPath(preparationId));
				
				URIBuilder uriBuilders = this.uriBuild(param);
				String responseTxt = this.callHttp(uriBuilders, "GET", null);
				
				JsonObject persistantPreparationInfo = new JsonParser().parse(responseTxt).getAsJsonObject();
				String datasetId = persistantPreparationInfo.get("dataSetId").getAsString();
				JsonArray stepIds = persistantPreparationInfo.get("steps").getAsJsonArray();
				
				// index 0????????? ??????????????? ??????????????? ??????
			    for(int j = stepIds.size() - 1; j >= 1; j--) {
			    	File persistentStepFile = new File(preparationsLocation + "/PersistentStep-" + stepIds.get(j).getAsString());
		    		if(persistentStepFile.isFile()) {
		        		try (InputStream in = Files.newInputStream(Paths.get(persistentStepFile.getPath()), StandardOpenOption.READ); GZIPInputStream gZIPInputStream = new GZIPInputStream(in)) {
		        			String persistentStep = IOUtils.toString(gZIPInputStream, "UTF-8");
		        			
		        			if(StringUtils.isNotBlank(persistentStep)) {
			        			JsonObject gsonObjectPersistentStep = new JsonParser().parse(persistentStep).getAsJsonObject();

			        			String contentId = gsonObjectPersistentStep.get("content").getAsString();
			        			// PreparationActions ??????
			        			File preparationActionsFile = new File(preparationsLocation + "/PreparationActions-" + contentId); 
			        			if(preparationActionsFile.isFile()) {
			        				preparationActionsFile.delete();        				
			        			}	
			        			
			        			if(null != gsonObjectPersistentStep.get("rowMetadata")) {
				        			String rowMetadataId = gsonObjectPersistentStep.get("rowMetadata").getAsString();
				        			
				        			// StepRowMetadata ??????
				        			File stepRowMetadataFile = new File(preparationsLocation + "/StepRowMetadata-" + rowMetadataId); 
				        			if(stepRowMetadataFile.isFile()) {
				            			stepRowMetadataFile.delete();        				
				        			}				        			
			        			}
		        			}
		    	        } 
		        		catch (IOException e) {
		        			e.printStackTrace();
		    	        } 
		        		finally {
		        			// PersistentStep ??????
		        			persistentStepFile.delete();
		    	        }    			
		    		}		    	
			    }

				// Preparation ??????
				param.put("path", this.getDeletePreparationPath(preparationId));    		
				uriBuilders = this.uriBuild(param);
				
				this.callHttp(uriBuilders, "DELETE", null);		
			}
		}
	}
	
	// ???????????? ??????
	public void deleteDataset() {
		File dir = new File(datasetLocation);
		File files[] = dir.listFiles();
		
		Map<String, String> param = new HashMap<>();
		for (int i = 0; i < files.length; i++) {
			if(-1 < files[i].getName().indexOf("sjpr")) {
				param.put("path", this.getDeleteDatasetPath(files[i].getName()));
				URIBuilder uriBuilders = this.uriBuild(param);
				
				this.callHttp(uriBuilders, "DELETE", null);				
			}
		}
	}	
}
