package hqr.o365.service;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException.BadRequest;
import org.springframework.web.client.HttpClientErrorException.NotFound;
import org.springframework.web.client.HttpClientErrorException.TooManyRequests;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException.BadGateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import cn.hutool.core.util.URLUtil;
import hqr.o365.dao.TaAppRptRepo;
import hqr.o365.dao.TaMasterCdRepo;
import hqr.o365.dao.TaOfficeInfoRepo;
import hqr.o365.domain.TaAppRpt;
import hqr.o365.domain.TaMasterCd;
import hqr.o365.domain.TaOfficeInfo;
import hqr.o365.service.ValidateAppInfo;

@Service
public class ScanAppStatusService implements SchedulingConfigurer{
	
	private RestTemplate restTemplate = new RestTemplate();
	
	private String cron = "0 0 4 */2 * ?";

	@Autowired
	private TaOfficeInfoRepo toi;
	
	@Autowired
	private TaAppRptRepo tar;
	
	@Autowired
	private ValidateAppInfo vai;
	
	@Autowired
	private TaMasterCdRepo tmc;
	
	@Value("${UA}")
    private String ua;
	
	private String force = "N";
	
	public String getForce() {
		return force;
	}
	
	public void setForce(String force) {
		this.force = force;
	}

	public void scan() {
		Optional<TaMasterCd> cdd = tmc.findById("GEN_APP_RPT");
		if(cdd.isPresent()) {
			TaMasterCd cdl = cdd.get();
			if("Y".equals(cdl.getCd())) {
				Optional<TaMasterCd> opt2 = tmc.findById("GEN_APP_RPT_SEED");
				//default is 1000
				int seedRange = 1000;
				int sleepMins = 0;
				if(opt2.isPresent()) {
					String seedStr = opt2.get().getCd();
					try {
						seedRange = Integer.valueOf(seedStr);
					}
					catch (Exception e) {
						System.out.println("Exception on convert, force to 1000");
					}
				}
				if(seedRange>0) {
					SecureRandom rand = new SecureRandom();
					sleepMins = rand.nextInt(seedRange);
				}
				//sleep sleepMins mins
				try {
					System.out.println("Sleep "+sleepMins+ " mins to generate the overall report");
					Thread.sleep(sleepMins * 60 * 1000);
				}
				catch (Exception e) {}
				
				execute("A");
			}
			else {
				System.out.println("Overall report is disabled");
			}
		}
	}
	
	@CacheEvict(value="cacheApprpt", allEntries = true)
	public void execute(String type) {
		//clean up the report table
		tar.deleteAll();
		tar.flush();
		
		//copy the tenant id from ta_office_info to ta_app_rpt
		List<TaOfficeInfo> list =  toi.findAll();
		if(list!=null) {
			for (int i = 0; i < list.size(); i++) {
				TaOfficeInfo tt = list.get(i);
				TaAppRpt rptEnti = new TaAppRpt();
				rptEnti.setTenantId(tt.getTenantId());
				rptEnti.setAppId(tt.getAppId());
				rptEnti.setSecretId(tt.getSecretId());
				rptEnti.setRemarks(tt.getRemarks());
				tar.saveAndFlush(rptEnti);
			}
		}
		
		//get all the info from ta_app_rpt and scan it
		List<TaAppRpt>  appList = tar.findAll();
		if(appList!=null) {
			String roleId = "62e90394-69f5-4237-9190-012177145e10";
			Optional<TaMasterCd> opt = tmc.findById("DEFAULT_ADMIN_ROLE_ID");
			if(opt.isPresent()) {
				TaMasterCd cd = opt.get();
				roleId = cd.getCd();
			}
			for (TaAppRpt taAppRpt : appList) {
				String accessToken = "";
				//before process sleep it
				sleepMins(type);
				
				if(vai.checkAndGet(taAppRpt.getTenantId(), taAppRpt.getAppId(), taAppRpt.getSecretId())) {
					accessToken = vai.getAccessToken();
				}
				if(!"".equals(accessToken)) {
					//get total admin user
					String endpoint1 = "https://graph.microsoft.com/v1.0/directoryRoles/roleTemplateId="+roleId+"/members/$count";
					HttpHeaders headers = new HttpHeaders();
					headers.set(HttpHeaders.USER_AGENT, ua);
					headers.add("Authorization", "Bearer "+accessToken);
					headers.add("ConsistencyLevel", "eventual");
					String body="";
					HttpEntity<String> requestEntity = new HttpEntity<String>(body, headers);
					try {
						ResponseEntity<String> response= restTemplate.exchange(URLUtil.decode(endpoint1), HttpMethod.GET, requestEntity, String.class);
						if(response.getStatusCodeValue()==200) {
							String totalAdmin = response.getBody();
							taAppRpt.setTotalGlobalAdmin(totalAdmin);
							System.out.println("Total Global admin for "+taAppRpt.getTenantId()+ " is "+totalAdmin);
						}
					}
					catch (Exception e) {
					}
					
					//get active admin
					String endpoint2 = "https://graph.microsoft.com/v1.0/directoryRoles/roleTemplateId="+roleId+"/members/$count?"+"$filter=accountEnabled eq true";
					HttpHeaders headers2 = new HttpHeaders();
					headers2.set(HttpHeaders.USER_AGENT, ua);
					headers2.add("Authorization", "Bearer "+accessToken);
					headers2.add("ConsistencyLevel", "eventual");
					String body2="";
					HttpEntity<String> requestEntity2 = new HttpEntity<String>(body2, headers2);
					try {
						ResponseEntity<String> response2= restTemplate.exchange(URLUtil.decode(endpoint2), HttpMethod.GET, requestEntity2, String.class);
						if(response2.getStatusCodeValue()==200) {
							String activeAdmin = response2.getBody();
							taAppRpt.setEnableGlobalAdmin(activeAdmin);
							System.out.println("Total active admin for "+taAppRpt.getTenantId()+ " is "+activeAdmin);
						}
						else {
						}
					}
					catch (Exception e) {
					}
					
					//get inactive admin
					String endpoint3 = "https://graph.microsoft.com/v1.0/directoryRoles/roleTemplateId="+roleId+"/members/$count?"+"$filter=accountEnabled eq false";
					HttpHeaders headers3 = new HttpHeaders();
					headers3.set(HttpHeaders.USER_AGENT, ua);
					headers3.add("Authorization", "Bearer "+accessToken);
					headers3.add("ConsistencyLevel", "eventual");
					String body3="";
					HttpEntity<String> requestEntity3 = new HttpEntity<String>(body3, headers3);
					try {
						ResponseEntity<String> response3= restTemplate.exchange(URLUtil.decode(endpoint3), HttpMethod.GET, requestEntity3, String.class);
						if(response3.getStatusCodeValue()==200) {
							String inActiveAdmin = response3.getBody();
							taAppRpt.setDisableGloablAdmin(inActiveAdmin);
							System.out.println("Total inactive admin for "+taAppRpt.getTenantId()+ " is "+inActiveAdmin);
						}
					}
					catch (Exception e) {
					}
					
					//get total user
					String endpoint4 = "https://graph.microsoft.com/v1.0/users/$count";
					HttpHeaders headers4 = new HttpHeaders();
					headers4.set(HttpHeaders.USER_AGENT, ua);
					headers4.add("Authorization", "Bearer "+accessToken);
					headers4.add("ConsistencyLevel", "eventual");
					String body4="";
					HttpEntity<String> requestEntity4 = new HttpEntity<String>(body4, headers4);
					try {
						ResponseEntity<String> response4= restTemplate.exchange(URLUtil.decode(endpoint4), HttpMethod.GET, requestEntity4, String.class);
						if(response4.getStatusCodeValue()==200) {
							String totalUser = response4.getBody();
							taAppRpt.setTotalUser(totalUser);
							System.out.println("Total user for "+taAppRpt.getTenantId()+ " is "+totalUser);
						}
					}
					catch (Exception e) {
					}
					
					//check spo
					/*
					String endpoint5 = "https://graph.microsoft.com/v1.0/sites/root";
					HttpHeaders headers5 = new HttpHeaders();
					headers5.set(HttpHeaders.USER_AGENT, ua);
					headers5.add("Authorization", "Bearer "+accessToken);
					headers5.add("ConsistencyLevel", "eventual");
					String body5="";
					HttpEntity<String> requestEntity5 = new HttpEntity<String>(body5, headers5);
					try {
						ResponseEntity<String> response5 = restTemplate.exchange(URLUtil.decode(endpoint5), HttpMethod.GET, requestEntity5, String.class);
						//200 -> OD is normal
						//429 -> SPO=0
						//400 -> No OD
						if(response5.getStatusCodeValue()==200) {
							taAppRpt.setSpo("可用");
						}
					}
					catch (Exception e) {
						if(e instanceof BadRequest) {
							taAppRpt.setSpo("无SPO订阅");
						}
						else if(e instanceof TooManyRequests || e instanceof BadGateway) {
							taAppRpt.setSpo("不可用");
						}
						else {
							taAppRpt.setSpo("未知的");
						}
					}
					*/
					checkSPO(taAppRpt, accessToken);
					System.out.println("SPO for "+taAppRpt.getTenantId()+ " is "+taAppRpt.getSpo());
					
					taAppRpt.setRptDt(new Date());
					tar.saveAndFlush(taAppRpt);
				}
			}
		}
	}
 
	/*
	 *  Type is A or M
	 *  A is auto
	 *  M is manual
	 */
	private void sleepMins(String type) {
		if("A".equals(type)) {
			int stime = 15;
			Optional<TaMasterCd> opt1 = tmc.findById("GEN_APP_RPT_DELAY_MINS_AUTO");
			String stimeStr = opt1.get().getCd();
			try {
				stime = Integer.parseInt(stimeStr);
				if(stime>0) {
					SecureRandom ran = new SecureRandom();
					stime = ran.nextInt(stime);
				}
			}
			catch (Exception e) {}
			
			try {
				System.out.println("Sleep "+stime+ " mins ");
				Thread.sleep(stime * 1000 * 60);
			} catch (InterruptedException e) {}
		}
		else {
			int stime = 0;
			Optional<TaMasterCd> opt1 = tmc.findById("GEN_APP_RPT_DELAY_MINS_MANUAL");
			String stimeStr = opt1.get().getCd();
			try {
				stime = Integer.parseInt(stimeStr);
				if(stime>0) {
					SecureRandom ran = new SecureRandom();
					stime = ran.nextInt(stime);
				}
			}
			catch (Exception e) {}
			
			try {
				System.out.println("Sleep "+stime+ " mins ");
				Thread.sleep(stime * 1000 * 60);
			} catch (InterruptedException e) {}
		}
	}
	
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(() -> {
        	scan();
        }, triggerContext -> {
            CronTrigger trigger = new CronTrigger(cron);
            return trigger.nextExecutionTime(triggerContext);
        });
    }
    public void setCron(String cron) {
        System.out.println("old cron："+this.cron+" new cron："+cron);
        this.cron = cron;
    }
    public String getCron() {
        return this.cron;
    }
	
	private void checkSPO(TaAppRpt taAppRpt, String accessToken) {
		List<String> testUserList = new ArrayList<String>();

		String endpoint1 = "https://graph.microsoft.com/v1.0/users?$select=userPrincipalName";
		HttpHeaders headers1 = new HttpHeaders();
		headers1.set(HttpHeaders.USER_AGENT, ua);
		headers1.add("Authorization", "Bearer "+accessToken);
		String body1="";
		HttpEntity<String> requestEntity1 = new HttpEntity<String>(body1, headers1);
		try {
			ResponseEntity<String> response1 = restTemplate.exchange(URLUtil.decode(endpoint1), HttpMethod.GET, requestEntity1, String.class);
			if(response1.getStatusCodeValue()==200) {
				JSONObject jo = JSON.parseObject(response1.getBody());
				JSONArray ja = jo.getJSONArray("value");
				int userCnt = ja.size();
				if(userCnt<2) {
					String userPrincipalName = ja.getJSONObject(0).getString("userPrincipalName");
					testUserList.add(userPrincipalName);
				}
				else {
					//get 2 consecutive random user
					SecureRandom sran = new SecureRandom();
					int r0 = sran.nextInt(userCnt);
					int r1 = 0;
					if(r0+1>=userCnt) {
						r1 = r0 - 1;
					}
					else {
						r1 = r0 + 1;
					}
					String userPrincipalName1 = ja.getJSONObject(r0).getString("userPrincipalName");
					String userPrincipalName2 = ja.getJSONObject(r1).getString("userPrincipalName");
					testUserList.add(userPrincipalName1);
					testUserList.add(userPrincipalName2);
				}
			}
		}
		catch (Exception e) {
		}
		
		//200 -> has SPO license and init the OD
		//401 -> not init OD
		//403 -> no license
		//404 -> SPO0 or user does not reset password
		//400 -> ?
		//429 -> SPO0 (All user 429, no matter has license or not)
		//592 -> ?
		//if 2 users = 429 or 404, then all consider as SPO0
		String spoStatus = "未知的";
		int spo0Cnt = 0;
		if(testUserList.size()>0) {
			for(String userId: testUserList) {
				String endpoint2 = "https://graph.microsoft.com/v1.0/users/"+userId+"/drive";
				HttpHeaders headers2 = new HttpHeaders();
				headers2.set(HttpHeaders.USER_AGENT, ua);
				headers2.add("Authorization", "Bearer "+accessToken);
				String body2="";
				HttpEntity<String> requestEntity2 = new HttpEntity<String>(body2, headers2);
				
				restTemplate.setErrorHandler(new ResponseErrorHandler() {
					@Override
					public boolean hasError(ClientHttpResponse response) throws IOException {
						return false;
					}
					@Override
					public void handleError(ClientHttpResponse response) throws IOException {}
				});
				ResponseEntity<String> response2 = restTemplate.exchange(URLUtil.decode(endpoint2), HttpMethod.GET, requestEntity2, String.class);
				String body = response2.getBody();
				//404 maybe SPO0
				if(response2.getStatusCodeValue()==404) {
					JSONObject jb = JSON.parseObject(body);
					JSONObject jb404 = jb.getJSONObject("error");
					String errorCd = jb404.getString("code");
					if("UnknownError".equalsIgnoreCase(errorCd)) {
						spo0Cnt ++;
					}
				}
				//429 SPO0
				else if(response2.getStatusCodeValue()==429) {
					spo0Cnt ++;
				}
				//other value not SPO0
				else {
					break;
				}

			}
			
			if(spo0Cnt==testUserList.size()) {
				spoStatus = "不可用";
			}
			else {
				spoStatus = "可用";
			}
			taAppRpt.setSpo(spoStatus);
		}
		else {
			taAppRpt.setSpo("未知的");
		}
	}
}
