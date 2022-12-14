package kr.co.digitalship.dprep.custom.schedule.job;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;

import org.apache.commons.lang.StringUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.PersistJobDataAfterExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.talend.dataprep.api.dataset.statistics.SemanticDomain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import kr.co.digitalship.dprep.custom.PropertiesUtil;
import kr.co.digitalship.dprep.custom.Singleton;
import kr.co.digitalship.dprep.custom.redis.SpringRedisTemplateUtil;
import kr.co.digitalship.dprep.custom.schedule.CustomQuartzJobBean;
import kr.co.digitalship.dprep.custom.schedule.util.DprepUtil;
import kr.co.digitalship.dprep.custom.schedule.vo.ProcessingInfomationVO;

@Component
@PersistJobDataAfterExecution
@DisallowConcurrentExecution
@ConditionalOnBean(type = "kr.co.digitalship.dprep.custom.schedule.QuartzConfig")
public class Job3CopyRecipe extends CustomQuartzJobBean {
	private static final Logger LOGGER = LoggerFactory.getLogger(Job3CopyRecipe.class);

	@Value("${dataprep.node.no:0}")
	private int nodeNo;
	
	@Value("${schedule.job.dependence.wait:0}")
	private int dependenceWait;
	
	//@Value("${kweather.preparation.replace_on_value.idx:-1}")
	//private int replaceOnValueIdx;
	
	//@Value("${kweather.preparation.replace_on_value.value:}")
	//private String replaceOnValueValue;
	
	@Value("${kweather.preparation.domain_change.idx:-1}")
	private int[] domainChangeIdx;
	
	@Value("${kweather.preparation.domain_change.value:}")
	private String[] domainChangeValue;

	@Value("${schedule.job3.cronExp:}")
	private String cronExp;		
	
	@Autowired	
	private SpringRedisTemplateUtil springRedisTemplateUtil;
	
	@Autowired
	private AbstractDatasetPattern exportPattern;
	
	@Autowired
	private DprepUtil dprepUtil;
	
	private ReentrantLock reentrantLock;
	
	public Job3CopyRecipe() {
		super();
		
		setJobName(this.getClass().getSimpleName());
		setTriggerName(this.getClass().getSimpleName().replace("Job", "Trigger"));
		setGroup("ProfilingBatch");
		setCronExp(cronExp);
		setUseJobListener(true);
		setUseTriggerListener(true);
	}
	
	@PostConstruct
	public void init() {
		PropertiesUtil properties = Singleton.getInstance().getPropertiesUtil();
		
		nodeNo = Integer.parseInt(properties.getProperty("dataprep.node.no"));
		dependenceWait = Integer.parseInt(properties.getProperty("schedule.job.dependence.wait"));
		//replaceOnValueIdx = Integer.parseInt(properties.getProperty("kweather.preparation.replace_on_value.idx"));
		//replaceOnValueValue = properties.getProperty("preparation.replace_on_value.value");
		String[] domainChangeIdxTemp = properties.getProperty("kweather.preparation.domain_change.idx").trim().split("\\s*,\\s*");
		domainChangeIdx = new int[domainChangeIdxTemp.length];
		for(int i = 0, len = domainChangeIdx.length; i < len; i++) {
			domainChangeIdx[i] = Integer.parseInt(domainChangeIdxTemp[i]);
		}
		domainChangeValue = properties.getProperty("kweather.preparation.domain_change.value").trim().split("\\s*,\\s*");
		cronExp = properties.getProperty("schedule.job3.cronExp");

		setCronExp(cronExp);
	}

	@Override	
	public void executeInternal(JobExecutionContext context) throws JobExecutionException {
		LOGGER.debug(String.format(context.getJobDetail().getKey().getName() + " executeInternal (%d)", nodeNo));
		
		List<String> jobStatusNode = new ArrayList<String>(Arrays.asList(new String[]{"DONE", "DONE", "RUNNING", "NEW"}));
		springRedisTemplateUtil.valueSet("JOB_STATUS_NODE_" + nodeNo, jobStatusNode);        		
		
		// 1. job2?????? ?????? dataset ??? ????????? ?????? - ????????????, ????????????, ????????? :: ??????????????? ???????????? ?????? 70% ?????? ???????????? ??????. ( ?????? ???????????? ????????? ????????? ?????? ????????? ?????? )
		Gson gson = new Gson();
		String jsonStr = (String)springRedisTemplateUtil.valueGet("LIST_OF_DATASET_INFO_" + nodeNo);
		if(StringUtils.isNotEmpty(jsonStr)) {
			JsonArray datasetsInfo = new JsonParser().parse(jsonStr).getAsJsonArray();
			//jsonStr = null; // ?????? ????????? ?????? null ?????????
			
			// 1. ?????? ????????? ????????? ????????? ??????
			List<ProcessingInfomationVO> listOfDatasetInfo = gson.fromJson(datasetsInfo, new TypeToken<List<ProcessingInfomationVO>>() {}.getType());
			//datasetsInfo = null; // ?????? ????????? ?????? null ?????????
			
			// 2. ?????? ????????? ?????? + ?????? ?????? 
			listOfDatasetInfo = exportPattern.getIncludeMetaData(listOfDatasetInfo);

			// 3. ?????? ????????? ?????? + ?????? ?????? + ?????? ?????? preparation ?????? 
			listOfDatasetInfo = exportPattern.getIncludePreparationTargetInfo(listOfDatasetInfo);
			
			reentrantLock = new ReentrantLock();
			boolean flagInfoRewrite = false;
			for(int i = listOfDatasetInfo.size() - 1; i >= 0; i--) {
				ProcessingInfomationVO processingInfomationVO = listOfDatasetInfo.get(i);
				if(null == processingInfomationVO.getCopyTargetPreparationInfoVO()) {
					// ?????? ???????????? ?????? (?????? ?????? ???????????? ?????? ??????)
					List<String> datasetIds = processingInfomationVO.getDatasetIds();

			    	boolean isPossibleLock = reentrantLock.isLocked(); // Lock??? ??? ??? ????????? ??????
			    	if(!isPossibleLock) {
			    		try {
							reentrantLock.lock();
							
							dprepUtil.deleteDatasetById(datasetIds, dependenceWait);
							
							Thread.sleep(1000);
						} 
			    		catch (InterruptedException e) {
							e.printStackTrace();
						}
			    		finally {
			    			reentrantLock.unlock();
			    		}
			    	}
					
					listOfDatasetInfo.remove(i);
					flagInfoRewrite = true;
				}
			}
			
			// 4. ?????? ?????? preparation ????????? ?????? ?????? ????????? ?????????. 
			if(flagInfoRewrite) {
				springRedisTemplateUtil.valueSet("INCLUDE_PREPARATION_TARGET_INFO_" + nodeNo, gson.toJson(listOfDatasetInfo));					
			}

			// 5. 4??? ????????? PersistancePreparation ??????
			List<ProcessingInfomationVO> listOfProcessingInfomationVO = null;
			if(null == listOfDatasetInfo || 0 == listOfDatasetInfo.size()) {
				// ??????
				LOGGER.debug(String.format(context.getJobDetail().getKey().getName() + " executeInternal - exit() (%d)", nodeNo));
				exit();
				
				return;
			}
			else {
				listOfProcessingInfomationVO = exportPattern.copyPreparation(listOfDatasetInfo, dependenceWait);					
			}
			
			// 6. ?????? ????????? ?????? ?????? ??? ????????? ?????? k-weather
			for(int i = 0, len = listOfProcessingInfomationVO.size(); i < len; i++) {
		    	boolean isPossibleLock = reentrantLock.isLocked(); // Lock??? ??? ??? ????????? ??????
		    	if(!isPossibleLock) {
					ProcessingInfomationVO processingInfomationVO = listOfProcessingInfomationVO.get(i);
					
					List<String> preparationIds = processingInfomationVO.getPreparationIds();
					//String delimiter = processingInfomationVO.getMetadataVO().getDelimiter();
					String[] headers = processingInfomationVO.getMetadataVO().getHeaders().replace("[", "").replace("]", "").split(",");
					
					List<String> columnsDomains = processingInfomationVO.getCopyTargetPreparationInfoVO().getColumnsDomains();
					List<List<SemanticDomain>> semanticDomains = processingInfomationVO.getCopyTargetPreparationInfoVO().getSemanticDomains();

					// ?????? ???????????? ?????? ????????? ????????? ?????? ??????...
					try {
						reentrantLock.lock();
						
						JsonObject gsonObject = new JsonObject();
						JsonArray gsonArray = new JsonArray();
			
						for(int j = 0, jLen = domainChangeIdx.length; j < jLen; j++) {
							List<SemanticDomain> semanticDomain = semanticDomains.get(domainChangeIdx[j]);
							
							for(int k = 0, kLen = semanticDomain.size(); k < kLen; k++) {
								SemanticDomain semanticDn = semanticDomain.get(k);
								if(domainChangeValue[j].equals(semanticDn.getId()) && !domainChangeValue[j].equals(columnsDomains.get(domainChangeIdx[j]))) {
									gsonArray.add(dprepUtil.getDomainChange(domainChangeIdx[j], headers[domainChangeIdx[j]], String.valueOf(semanticDn.getScore()), semanticDn.getId(), semanticDn.getLabel()));
									break;
								}
							}
						}
						gsonObject.add("actions", gsonArray);
						
						dprepUtil.executeAction(preparationIds, gsonObject);
						
						Thread.sleep(1000);
					} 
					catch (InterruptedException e) {
						e.printStackTrace();
					}
					finally {
						reentrantLock.unlock();
					}
		    	}
			}

			springRedisTemplateUtil.valueSet("LIST_OF_EXPORT_" + nodeNo, gson.toJson(listOfProcessingInfomationVO));
			//listOfProcessingInfomationVO = null; // ?????? ????????? ?????? null ?????????
			
			context.getJobDetail().getJobDataMap().put("job3IsDonePossible", true);
		}
	}
	
	private void exit() {
		dprepUtil.deleteDprepData(-1);
/*		
		List<String> keys = new ArrayList<>();

		keys.add("INCLUDE_PREPARATION_TARGET_INFO_" + nodeNo);			
		keys.add("INCLUDE_META_INFO_" + nodeNo);
		keys.add("LIST_OF_DATASET_INFO_" + nodeNo);
		keys.add("LIST_OF_FILES_TO_BE_PROCESSED_" + nodeNo);
		//keys.add("JOB_RUNNING_START_TIME_" + nodeNo);
		//keys.add("JOB_RUNNING_END_TIME_" + nodeNo);
		keys.add("TARGET_PREPARATION_CANDIDATE_" + nodeNo);		
		
		springRedisTemplateUtil.delete(keys);
*/		
	}
}
