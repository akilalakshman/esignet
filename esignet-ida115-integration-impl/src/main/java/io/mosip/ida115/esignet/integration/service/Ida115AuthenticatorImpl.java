/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.ida115.esignet.integration.service;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.authentication.common.service.helper.IdInfoHelper;
import io.mosip.authentication.common.service.impl.match.BioMatchType;
import io.mosip.authentication.common.service.integration.TokenIdManager;
import io.mosip.authentication.core.constant.IdAuthConfigKeyConstants;
import io.mosip.authentication.core.constant.IdAuthenticationErrorConstants;
import io.mosip.authentication.core.exception.IdAuthenticationBusinessException;
import io.mosip.authentication.core.indauth.dto.IdentityInfoDTO;
import io.mosip.authentication.core.spi.bioauth.CbeffDocType;
import io.mosip.authentication.core.spi.indauth.match.IdInfoFetcher;
import io.mosip.esignet.api.dto.KycAuthDto;
import io.mosip.esignet.api.dto.KycAuthResult;
import io.mosip.esignet.api.dto.KycExchangeDto;
import io.mosip.esignet.api.dto.KycExchangeResult;
import io.mosip.esignet.api.dto.KycSigningCertificateData;
import io.mosip.esignet.api.dto.SendOtpDto;
import io.mosip.esignet.api.dto.SendOtpResult;
import io.mosip.esignet.api.exception.KycAuthException;
import io.mosip.esignet.api.exception.KycExchangeException;
import io.mosip.esignet.api.exception.KycSigningCertificateException;
import io.mosip.esignet.api.exception.SendOtpException;
import io.mosip.esignet.api.spi.Authenticator;
import io.mosip.esignet.api.util.ErrorConstants;
import io.mosip.ida115.esignet.integration.dto.IdaKycAuthRequest;
import io.mosip.ida115.esignet.integration.dto.IdaKycExchangeResponse;
import io.mosip.ida115.esignet.integration.dto.IdaKycResponse;
import io.mosip.ida115.esignet.integration.dto.IdaResponseWrapper;
import io.mosip.ida115.esignet.integration.dto.IdaSendOtpRequest;
import io.mosip.ida115.esignet.integration.helper.IdentityDataStore;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.signature.dto.JWTSignatureRequestDto;
import io.mosip.kernel.signature.service.SignatureService;
import lombok.extern.slf4j.Slf4j;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;


@ConditionalOnProperty(value = "mosip.esignet.integration.authenticator", havingValue = "Ida115AuthenticatorImpl")
@Component
@Slf4j
public class Ida115AuthenticatorImpl implements Authenticator {

	private static final String ATTRIB_NAME_LANG_SEPERATOR="_";
	public static final String SIGNATURE_HEADER_NAME = "signature";
    public static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    public static final String KYC_EXCHANGE_TYPE = "oidc";
	private static final String HASH_ALGORITHM_NAME = "SHA-256";
	public static final String SUBJECT = "sub";
	public static final String CLAIMS_LANG_SEPERATOR = "#";
	public static final String FORMATTED = "formatted";
	public static final String EMPTY = "";

    @Value("${mosip.esignet.authenticator.ida-kyc-id:mosip.identity.kyc}")
    private String kycId;
    
    @Value("${mosip.esignet.authenticator.ida-auth-only-id:mosip.identity.auth}")
    private String authOnlyId;

    @Value("${mosip.esignet.authenticator.ida-version:1.0}")
    private String idaVersion;

    @Value("${mosip.esignet.authenticator.ida-domainUri}")
    private String idaDomainUri;

    @Value("${mosip.esignet.authenticator.ida-env:Staging}")
    private String idaEnv;

    @Value("${mosip.esignet.authenticator.ida.kyc-url}")
    private String kycUrl;
    
    @Value("${mosip.esignet.authenticator.ida.auth-url}")
    private String authUrl;

    @Value("${mosip.esignet.authenticator.ida.otp-channels}")
    private List<String> otpChannels;

    @Value("${mosip.esignet.authenticator.ida.get-certificates-url}")
    private String getCertsUrl;
    
    @Value("${mosip.esignet.authenticator.ida.application-id:IDA}")
    private String applicationId;
    
    @Value("${mosip.esignet.authenticator.ida.reference-id:SIGN}")
    private String referenceId;
    
    @Value("${mosip.esignet.authenticator.ida.client-id}")
    private String clientId;

    @Value("${mosip.esignet.authenticator.ida.wrapper.auth.partner.id}")
    private String esignetAuthPartnerId;
    
    
    @Value("${mosip.esignet.authenticator.ida.wrapper.auth.partner.apikey}")
    private String esignetAuthPartnerApiKey;
    
    @Value("${ida.idp.consented.picture.attribute.name:picture}")
	private String consentedFaceAttributeName;
    
    @Value("${ida.idp.consented.name.attribute.name:name}")
	private String consentedNameAttributeName;

	@Value("${ida.idp.consented.address.attribute.name:address}")
	private String consentedAddressAttributeName;

	@Value("${ida.idp.consented.individual_id.attribute.name:individual_id}")
	private String consentedIndividualAttributeName;

	@Value("${ida.idp.consented.picture.attribute.prefix:data:image/jpeg;base64,}")
	private String consentedPictureAttributePrefix;

	@Value("${mosip.ida.idp.consented.address.subset.attributes:}")
	private String[] addressSubsetAttributes;
	
	@Value("${mosip.ida.idp.consented.address.subset.attributes:}")
	private String[] nameSubsetAttributes;

	@Value("${ida.idp.consented.address.value.separator: }")
	private String addressValueSeparator;
	
	@Value("${ida.idp.consented.name.value.separator: }")
	private String addressNameSeparator;
	
	@Value("${ida.kyc.send-face-as-cbeff-xml:false}")
	private boolean idaSentFaceAsCbeffXml;
	
	@Value("${mosip.ida.kyc.exchange.sign.include.certificate:false}")
	private boolean includeCertificate;

	/** The sign applicationid. */
	@Value("${mosip.ida.kyc.exchange.sign.applicationid:IDA_KYC_EXCHANGE}")
	private String kycExchSignApplicationId;
	
	/** The key splitter. */
//	@Value("${mosip.kernel.data-key-splitter}")
	@Value("${" + IdAuthConfigKeyConstants.KEY_SPLITTER + "}")
	private String keySplitter;

	@Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    HelperService helperService;
    
    @Autowired
    private IdentityDataStore identityDataStore;
    
    @Autowired
    private TokenIdManager tokenIdManager;
    
    @Autowired
    private IdInfoHelper idInfoHelper;
    	
    /** The token ID length. */
	@Value("${mosip.ida.kyc.token.secret}")
	private String kycTokenSecret;
	
	@Autowired
	private SignatureService signatureService;
	
	@Value("${mosip.esignet.authenticator.ida.auth-only-enabled:false}")
	private boolean authOnlyEnabled;
	
    @Override
    public KycAuthResult doKycAuth(String relyingPartyId, String clientId, KycAuthDto kycAuthDto)
            throws KycAuthException {
        log.info("Started to build kyc request with transactionId : {} && clientId : {}",
                kycAuthDto.getTransactionId(), clientId);
        try {
            IdaKycAuthRequest idaKycAuthRequest = new IdaKycAuthRequest();
            idaKycAuthRequest.setId(authOnlyEnabled ? authOnlyId: kycId);
            idaKycAuthRequest.setVersion(idaVersion);
            idaKycAuthRequest.setRequestTime(HelperService.getUTCDateTime());
            idaKycAuthRequest.setDomainUri(idaDomainUri);
            idaKycAuthRequest.setEnv(idaEnv);
            idaKycAuthRequest.setConsentObtained(true);
            idaKycAuthRequest.setIndividualId(kycAuthDto.getIndividualId());
            idaKycAuthRequest.setTransactionID(kycAuthDto.getTransactionId());
            //Needed in pre-LTS version (such as 1.1.5.X)
            Map<String, Boolean> requestedAuth = new HashMap<>();
			idaKycAuthRequest.setRequestedAuth(requestedAuth);
			
            helperService.setAuthRequest(kycAuthDto.getChallengeList(), idaKycAuthRequest);

            //set signature header, body and invoke kyc auth endpoint
            String requestBody = objectMapper.writeValueAsString(idaKycAuthRequest);
            RequestEntity<?> requestEntity = RequestEntity
                    .post(UriComponentsBuilder.fromUriString(authOnlyEnabled? authUrl : kycUrl).pathSegment(esignetAuthPartnerId, esignetAuthPartnerApiKey).build().toUri())
                    .contentType(MediaType.APPLICATION_JSON_UTF8)
                    .header(SIGNATURE_HEADER_NAME, helperService.getRequestSignature(requestBody))
                    .header(AUTHORIZATION_HEADER_NAME, AUTHORIZATION_HEADER_NAME)
                    .body(requestBody);
            ResponseEntity<IdaResponseWrapper<IdaKycResponse>> responseEntity = restTemplate.exchange(requestEntity,
                    new ParameterizedTypeReference<IdaResponseWrapper<IdaKycResponse>>() {});

            if(responseEntity.getStatusCode().is2xxSuccessful() && responseEntity.getBody() != null) {
                IdaResponseWrapper<IdaKycResponse> responseWrapper = responseEntity.getBody();
				if (responseWrapper.getResponse() != null && (responseWrapper.getResponse().isAuthStatus()
						|| responseWrapper.getResponse().isKycStatus())) {
	                String psut = generatePsut(relyingPartyId, kycAuthDto.getIndividualId());
	                Tuple2<String, String> result = processKycResponse(responseWrapper, psut);
	                String kycToken = result.getT1();
	                String encryptedIdentityData = result.getT2();
	                identityDataStore.putEncryptedIdentityData(kycToken, psut, encryptedIdentityData);
					return new KycAuthResult(kycToken, psut);
                }
                
                log.error("Error response received from IDA status : {} && Errors: {}",
                		(responseWrapper.getResponse() != null && (responseWrapper.getResponse().isKycStatus() ||  responseWrapper.getResponse().isAuthStatus())), responseWrapper.getErrors());
                throw new KycAuthException(CollectionUtils.isEmpty(responseWrapper.getErrors()) ?
                         ErrorConstants.AUTH_FAILED : responseWrapper.getErrors().get(0).getErrorCode());
            }

            log.error("Error response received from IDA (Kyc-auth) with status : {}", responseEntity.getStatusCode());
		} catch (KycAuthException e) {
			throw e;
		} catch (Exception e) {
			log.error("KYC-auth failed with transactionId : {} && clientId : {}", kycAuthDto.getTransactionId(),
					clientId, e);
		}
        throw new KycAuthException(ErrorConstants.AUTH_FAILED);
    }

    
	private String generatePsut(String relyingPartyId, String individualId) throws Exception {
		return tokenIdManager.generateTokenId(individualId, relyingPartyId);
	}

	private Tuple2<String, String> processKycResponse(IdaResponseWrapper<IdaKycResponse> responseWrapper, String psut) throws DecoderException, NoSuchAlgorithmException {
		String kycToken = generateKycToken(responseWrapper.getTransactionID(), psut);
    	if(responseWrapper.getResponse() != null && responseWrapper.getResponse().isKycStatus()) {
    		IdaKycResponse response = responseWrapper.getResponse();
    		String encryptedIdentityData = response.getIdentity();
    		String combinedData = encryptedIdentityData;
    		String sessionKey = response.getSessionKey();
    		if(sessionKey != null) {
    			combinedData = CryptoUtil.encodeToURLSafeBase64(combineDataForDecryption(CryptoUtil.decodeURLSafeBase64(sessionKey), CryptoUtil.decodeURLSafeBase64(encryptedIdentityData)));
    		}
    		String thumbprint = response.getThumbprint();
    		if(thumbprint != null) {
    			combinedData = CryptoUtil.encodeToURLSafeBase64(CryptoUtil.combineByteArray(CryptoUtil.decodeURLSafeBase64(combinedData), getThumbprintBytes(thumbprint), EMPTY));
    		}
			return Tuples.of(kycToken, combinedData);
    	}
		return Tuples.of(kycToken, EMPTY);
	}


	private byte[] getThumbprintBytes(String thumbprint) throws DecoderException {
		try {
			return Hex.decodeHex(thumbprint);
		} catch (DecoderException e) {
			log.debug("Thumbprint is not hex. Trying to base64-decode");
			return CryptoUtil.decodeURLSafeBase64(thumbprint);
		}
	}
	
	/**
	 * Combine data for decryption.
	 *
	 * @param encryptedSessionKey the encrypted session key
	 * @param encryptedData the encrypted data
	 * @return the string
	 */
	private byte[] combineDataForDecryption(byte[] encryptedSessionKey, byte[] encryptedData) {
		return CryptoUtil.combineByteArray(encryptedData, encryptedSessionKey, keySplitter);
	}

	private String generateKycToken(String transactionID, String authToken) throws DecoderException, NoSuchAlgorithmException {
		String uuid = UUID.nameUUIDFromBytes((transactionID + System.nanoTime()).getBytes()).toString();
		return doGenerateKycToken(uuid, authToken);
	}
	
	private String doGenerateKycToken(String uuid, String idHash) throws DecoderException, NoSuchAlgorithmException {
		try {
			byte[] uuidBytes = uuid.getBytes();
			byte[] idHashBytes = getThumbprintBytes(idHash);
			ByteBuffer bBuffer = ByteBuffer.allocate(uuidBytes.length + idHashBytes.length);
			bBuffer.put(uuidBytes);
			bBuffer.put(idHashBytes);

			byte[] kycTokenInputBytes = bBuffer.array();
			return generateKeyedHash(kycTokenInputBytes);
		} catch (DecoderException e) {
			log.error("Error Generating KYC Token", e);
			throw e;
		}
	}
	
	public String generateKeyedHash(byte[] bytesToHash) throws java.security.NoSuchAlgorithmException {
		try {
			// Need to get secret from HSM  
			byte[] tokenSecret = CryptoUtil.decodeURLSafeBase64(kycTokenSecret);
			MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM_NAME);
			messageDigest.update(bytesToHash);
			messageDigest.update(tokenSecret);
			byte[] tokenHash = messageDigest.digest();

			return TokenEncoderUtil.encodeBase58(tokenHash);
		} catch (NoSuchAlgorithmException e) {
			log.error("Error generating Keyed Hash", e);
			throw e;
		}
	}
	
	@Override
    public KycExchangeResult doKycExchange(String relyingPartyId, String clientId, KycExchangeDto kycExchangeDto)
            throws KycExchangeException {
        log.info("Started to build kyc-exchange request with transactionId : {} && clientId : {}",
                kycExchangeDto.getTransactionId(), clientId);
        try {
        	String psut = generatePsut(relyingPartyId, kycExchangeDto.getIndividualId());
        	String encryptedIdentityData = identityDataStore.getEncryptedIdentityData(kycExchangeDto.getKycToken(), psut);
        	Map<String, Object> idResDTO;
        	if(encryptedIdentityData != null && !encryptedIdentityData.isEmpty()) {
	        	String decrptIdentityData = helperService.decrptData(encryptedIdentityData);
	        	idResDTO = objectMapper.readValue(CryptoUtil.decodeURLSafeBase64(decrptIdentityData), Map.class);
				Map<String, List<IdentityInfoDTO>> idInfo = IdInfoFetcher.getIdInfo(idResDTO);
				
				String respJson =idInfo.isEmpty() ? null:  buildKycExchangeResponse(psut, idInfo, kycExchangeDto.getAcceptedClaims(), List.of(kycExchangeDto.getClaimsLocales()), kycExchangeDto.getIndividualId());
				IdaResponseWrapper<IdaKycExchangeResponse> responseWrapper = new IdaResponseWrapper<>();
				IdaKycExchangeResponse respose = new IdaKycExchangeResponse();
				responseWrapper.setResponse(respose);
				respose.setEncryptedKyc(respJson);
	            return new KycExchangeResult(responseWrapper.getResponse().getEncryptedKyc());
        	} else {
    			log.info("Encrypted Identity data not found.");
        		idResDTO = Map.of(SUBJECT, psut);
        		String signedData = signWithPayload(objectMapper.writeValueAsString(idResDTO));
        		return new KycExchangeResult(signedData);
        	}
		} catch (KycExchangeException e) {
			throw e;
		} catch (Exception e) {e.printStackTrace();
			log.error("IDA Kyc-exchange failed with clientId : {}", clientId, e);
		}
        throw new KycExchangeException();
    }
	
	public String buildKycExchangeResponse(String subject, Map<String, List<IdentityInfoDTO>> idInfo, 
				List<String> consentedAttributes, List<String> consentedLocales, String idVid) throws IdAuthenticationBusinessException {
		
		log.info("Building claims response for PSU token..");
					
		Map<String, Object> respMap = new HashMap<>();
		Set<String> uniqueConsentedLocales = new HashSet<String>(consentedLocales);
		Map<String, String> mappedConsentedLocales = localesMapping(uniqueConsentedLocales);

		respMap.put(SUBJECT, subject);
		
		for (String attrib : consentedAttributes) {
			if (attrib.equals(SUBJECT))
				continue;
			if (attrib.equals(consentedIndividualAttributeName)) {
				respMap.put(attrib, idVid);
				continue;
			}
			List<String> idSchemaAttribute = idInfoHelper.getIdentityAttributesForIdName(attrib);
			Map<String, List<IdentityInfoDTO>> idInfoNameAndLangCorrected = getCorrectedIdInfoMap(idInfo);
			if (mappedConsentedLocales.size() > 0) {
				addEntityForLangCodes(mappedConsentedLocales, idInfo, respMap, attrib, idSchemaAttribute, idInfoNameAndLangCorrected);
			}
		}

		try {
			return signWithPayload(objectMapper.writeValueAsString(respMap));
		} catch (JsonProcessingException e) {
			throw new IdAuthenticationBusinessException(IdAuthenticationErrorConstants.UNABLE_TO_PROCESS, e);
		}
	}


	/**
	 * Correct the attribute name key, for example name_eng to name and put the Language (eng) into the list of idInfo language
	 * @param idInfo
	 * @return corrected idInfoMap
	 */
	private Map<String, List<IdentityInfoDTO>> getCorrectedIdInfoMap(Map<String, List<IdentityInfoDTO>> idInfo) {
		return idInfo.entrySet().stream()
				.collect(
				Collectors.groupingBy(entry -> {
					//correct the attribute name key, for example name_eng to name 
						String[] attribAndLang = entry.getKey().split(ATTRIB_NAME_LANG_SEPERATOR);
						try {
							List<String> identityAttributesForIdName = idInfoHelper.getIdentityAttributesForIdName(attribAndLang[0]);
							if(!identityAttributesForIdName.isEmpty()) {
								return identityAttributesForIdName.get(0);
							}
						} catch (IdAuthenticationBusinessException e) {
							log.debug("Error in getting id attribute for id name");
						}
						return attribAndLang[0];
					},
					//	put the Language (for example eng) into the list of idInfo language
					Collectors.flatMapping(entry -> {
						String[] attribAndLang = entry.getKey().split(ATTRIB_NAME_LANG_SEPERATOR);
						//Return the attributeName as Key
						String lang =  attribAndLang.length > 1? attribAndLang[1] : EMPTY;
						return entry.getValue().stream()
								.peek(idInfoEntry -> {
									if(!lang.isEmpty()) {
										idInfoEntry.setLanguage(lang);
									}
								});
					}, Collectors.toList())));
	}
	
	public String signWithPayload(String data) {
		JWTSignatureRequestDto request = new JWTSignatureRequestDto();
		request.setApplicationId(kycExchSignApplicationId);
		request.setDataToSign(CryptoUtil.encodeToURLSafeBase64(data.getBytes()));
		request.setIncludeCertHash(false);
		request.setIncludeCertificate(includeCertificate);
		request.setIncludePayload(true);
		request.setReferenceId(EMPTY);
		return signatureService.jwtSign(request).getJwtSignedData();
	}
	
	private void addEntityForLangCodes(Map<String, String> mappedConsentedLocales, Map<String, List<IdentityInfoDTO>> idInfo, 
			Map<String, Object> respMap, String consentedAttribute, List<String> idSchemaAttributes, Map<String, List<IdentityInfoDTO>> idInfoNameAndLangCorrected) 
			throws IdAuthenticationBusinessException {
	
		if (consentedAttribute.equals(consentedFaceAttributeName)) {
			if (!idInfo.keySet().contains(BioMatchType.FACE.getIdMapping().getIdname())) {
				log.info("Face Bio not found in DB. So not adding to response claims.");
				return;
			}
			Map<String, String> faceEntityInfoMap;
			String faceAttribName = CbeffDocType.FACE.getType().value();
			if (idaSentFaceAsCbeffXml) {
				faceEntityInfoMap = idInfoHelper.getIdEntityInfoMap(BioMatchType.FACE, idInfo,
						null);
			} else {
				List<IdentityInfoDTO> faceInfo = idInfo.get(faceAttribName);
				faceEntityInfoMap = faceInfo == null || faceInfo.isEmpty() ? Map.of()
						: Map.of(faceAttribName, faceInfo.get(0).getValue());
				
			}
			
			if (Objects.nonNull(faceEntityInfoMap) && !faceEntityInfoMap.isEmpty()) {
				String face = ConverterUtil.convertJP2ToJpeg(faceEntityInfoMap.get(faceAttribName));
				if (Objects.nonNull(face)) {
					respMap.put(consentedAttribute, consentedPictureAttributePrefix + face);
				}
			}
			return;
		}
	
		if (idSchemaAttributes.size() == 1) {
			List<IdentityInfoDTO> idInfoList = idInfoNameAndLangCorrected.get(idSchemaAttributes.get(0));
			
			if (Objects.isNull(idInfoList)) {
				log.info("Data not available in Identity Info for the claim. So not adding to response claims. Claim Name: " + idSchemaAttributes.get(0));
				return;
			}
			Map<String, String> mappedLangCodes = langCodeMapping(idInfoList);
			List<String> availableLangCodes = getAvailableLangCodes(mappedConsentedLocales, mappedLangCodes);
			if (availableLangCodes.size() == 1) {
				for (IdentityInfoDTO identityInfo : idInfoList) {
					String langCode = mappedLangCodes.get(availableLangCodes.get(0));
					if (identityInfo.getLanguage().equalsIgnoreCase(langCode)) {
						respMap.put(consentedAttribute, identityInfo.getValue());
					}
				}
			} else {
				if (availableLangCodes.size() > 0) {
					for (IdentityInfoDTO identityInfo : idInfoList) {
						for (String availableLangCode : availableLangCodes) {
							String langCode = mappedLangCodes.get(availableLangCode);
							if (identityInfo.getLanguage().equalsIgnoreCase(langCode)) {
								respMap.put(consentedAttribute + CLAIMS_LANG_SEPERATOR + availableLangCode, 
										identityInfo.getValue());
							}
						}
					}
				} else {
					respMap.put(consentedAttribute, idInfoList.get(0).getValue());
				}
			}
		} else {
			handleAttributeWithSubAttributes(mappedConsentedLocales, idSchemaAttributes, idInfoNameAndLangCorrected, respMap, consentedAddressAttributeName, addressValueSeparator, addressSubsetAttributes);
		}
	}
	
	private void handleAttributeWithSubAttributes(Map<String, String> mappedConsentedLocales, List<String> idSchemaAttributes, Map<String, List<IdentityInfoDTO>> idInfoNameAndLangCorrected, Map<String, Object> respMap, String consentedAttributeName, String valueSeparator, String[] subsetAttributes) throws IdAuthenticationBusinessException {
		if (mappedConsentedLocales.size() > 1) {
			for (String consentedLocale: mappedConsentedLocales.keySet()) {
				String consentedLocaleValue = mappedConsentedLocales.get(consentedLocale);
				if (subsetAttributes.length == 0) {
					log.info(String.format(
							"No %s subset attributes configured. Will return the address with formatted attribute.",
							consentedAttributeName));
					addFormattedSubAttributes(idSchemaAttributes, idInfoNameAndLangCorrected, consentedLocaleValue, respMap, true, 
						CLAIMS_LANG_SEPERATOR + consentedLocaleValue, consentedAttributeName, valueSeparator);
					continue;
				}
				addSubAttributesClaim(subsetAttributes, idInfoNameAndLangCorrected, consentedLocaleValue, respMap, true, 
						CLAIMS_LANG_SEPERATOR + consentedLocaleValue, consentedAttributeName, valueSeparator);
			}
		} else {
			String consentedLocale = mappedConsentedLocales.keySet().iterator().next();
			String consentedLocaleValue = mappedConsentedLocales.get(consentedLocale);
			if (subsetAttributes.length == 0) {
				log.info(String.format("No %s subset attributes configured. Will return the address with formatted attribute.",
						consentedAttributeName));
				addFormattedSubAttributes(idSchemaAttributes, idInfoNameAndLangCorrected, consentedLocaleValue, respMap, false, "", consentedAttributeName, valueSeparator);
				return;
			}
			
			addSubAttributesClaim(subsetAttributes, idInfoNameAndLangCorrected, consentedLocaleValue, respMap, false, "", consentedAttributeName, valueSeparator);
		}		
	}


	private void addFormattedSubAttributes(List<String> idSchemaAttributes, Map<String, List<IdentityInfoDTO>> idInfo, String localeValue, 
								Map<String, Object> respMap, boolean addLocale, String localeAppendValue, String consentedAttributeName, String valueSeparator) throws IdAuthenticationBusinessException {
		boolean langCodeFound = false;
		Map<String, String> attributesMap = new HashMap<>();
		StringBuilder identityInfoValue = new StringBuilder(); 
		for (String schemaAttrib: idSchemaAttributes) {
			List<String> idSchemaSubsetAttributes = idInfoHelper.getIdentityAttributesForIdName(schemaAttrib);
			for (String idSchemaAttribute : idSchemaSubsetAttributes) {
				List<IdentityInfoDTO> idInfoList = idInfo.get(idSchemaAttribute);
				if(idInfoList != null) {
					Map<String, String> mappedLangCodes = langCodeMapping(idInfoList);
					if (identityInfoValue.length() > 0) {
						identityInfoValue.append(valueSeparator);
					}
					if (mappedLangCodes.keySet().contains(localeValue)) {
						String langCode = mappedLangCodes.get(localeValue);
						for (IdentityInfoDTO identityInfo : idInfoList) { 
							if (identityInfoValue.length() > 0) {
								identityInfoValue.append(valueSeparator);
							}
							if (identityInfo.getLanguage().equals(langCode)) {
								langCodeFound = true;
								identityInfoValue.append(identityInfo.getValue());
							}
						}
					} else {
						if (Objects.nonNull(idInfoList) && idInfoList.size() == 1) {
							identityInfoValue.append(idInfoList.get(0).getValue());
						}
					}
				}
			}
		}
		//String identityInfoValueStr = identityInfoValue.toString();
		//String trimmedValue = identityInfoValueStr.substring(0, identityInfoValueStr.lastIndexOf(addressValueSeparator));
		attributesMap.put(FORMATTED + localeAppendValue, identityInfoValue.toString());
		if (langCodeFound && addLocale) {
			respMap.put(consentedAttributeName + localeAppendValue, (consentedAttributeName.equals(consentedAddressAttributeName)) ? attributesMap : identityInfoValue.toString());
		} else {
			respMap.put(consentedAttributeName, (consentedAttributeName.equals(consentedAddressAttributeName)) ? attributesMap : identityInfoValue.toString());
		}
	}
	
	private void addSubAttributesClaim(String[] addressAttributes, Map<String, List<IdentityInfoDTO>> idInfo, String consentedLocaleValue,
			Map<String, Object> respMap, boolean addLocale, String localeAppendValue, String consentedAddressAttributeName, String addressValueSeparator) throws IdAuthenticationBusinessException {
		boolean langCodeFound = false; //added for language data not available in identity info (Eg: fr)
		Map<String, String> addressMap = new HashMap<>();
		for (String addressAttribute : addressAttributes) {
			List<String> idSchemaSubsetAttributes = idInfoHelper.getIdentityAttributesForIdName(addressAttribute);
			StringBuilder identityInfoValue = new StringBuilder(); 
			for (String idSchemaAttribute : idSchemaSubsetAttributes) {
				List<IdentityInfoDTO> idInfoList = idInfo.get(idSchemaAttribute);
				Map<String, String> mappedLangCodes = langCodeMapping(idInfoList);
				if (identityInfoValue.length() > 0) {
					identityInfoValue.append(addressValueSeparator);
				}
				if (mappedLangCodes.keySet().contains(consentedLocaleValue)) {
					String langCode = mappedLangCodes.get(consentedLocaleValue);
					for (IdentityInfoDTO identityInfo : idInfoList) {
						if (identityInfoValue.length() > 0) {
							identityInfoValue.append(addressValueSeparator);
						}
						if (identityInfo.getLanguage().equals(langCode)) {
							langCodeFound = true;
							identityInfoValue.append(identityInfo.getValue());
						}
					}
				} else {
					if (Objects.nonNull(idInfoList) && idInfoList.size() == 1) {
						identityInfoValue.append(idInfoList.get(0).getValue());
					}
				}
			}
			// Added below condition to skip if the data is not available in DB. MOSIP-26472
			if (identityInfoValue.toString().trim().length() > 0) {
				addressMap.put(addressAttribute + localeAppendValue, identityInfoValue.toString());
			}
		}
		if (langCodeFound && addLocale) {
			respMap.put(consentedAddressAttributeName + localeAppendValue, addressMap);
		} else {
			respMap.put(consentedAddressAttributeName, addressMap);
		}
	}
	
	private Map<String, String> localesMapping(Set<String> locales) {
	
		Map<String, String> mappedLocales = new HashMap<>();
		for (String locale : locales) {
			if (locale.trim().length() == 0)
				continue;
			mappedLocales.put(locale, locale.substring(0, 2));
		}
		return mappedLocales;
	}
	
	private Map<String, String> langCodeMapping(List<IdentityInfoDTO> idInfoList) {
	
		Map<String, String> mappedLangCodes = new HashMap<>();
		if (Objects.nonNull(idInfoList)) {
			for (IdentityInfoDTO idInfo :  idInfoList) {
				if (Objects.nonNull(idInfo.getLanguage())) {
					mappedLangCodes.put(idInfo.getLanguage().substring(0,2), idInfo.getLanguage());
				}
			}
		}
		return mappedLangCodes;
	}
	
	private List<String> getAvailableLangCodes(Map<String, String> mappedLocales, Map<String, String> mappedLangCodes) {
		List<String> availableLangCodes = new ArrayList<>();
		for (String entry: mappedLocales.keySet()) {
			String locale = mappedLocales.get(entry);
			if (mappedLangCodes.keySet().contains(locale)) {
				availableLangCodes.add(locale);
			}
		}
		return availableLangCodes;
	}
	
    @Override
    public SendOtpResult sendOtp(String relyingPartyId, String clientId, SendOtpDto sendOtpDto)  throws SendOtpException {
        log.info("Started to build send-otp request with transactionId : {} && clientId : {}",
                sendOtpDto.getTransactionId(), clientId);
        try {
            IdaSendOtpRequest idaSendOtpRequest = new IdaSendOtpRequest();
            idaSendOtpRequest.setOtpChannel(sendOtpDto.getOtpChannels());
            idaSendOtpRequest.setIndividualId(sendOtpDto.getIndividualId());
            idaSendOtpRequest.setTransactionID(sendOtpDto.getTransactionId());
            return helperService.sendOTP(esignetAuthPartnerId, esignetAuthPartnerApiKey, idaSendOtpRequest);
        } catch (SendOtpException e) {
            throw e;
        } catch (Exception e) {
            log.error("send-otp failed with clientId : {}", clientId, e);
        }
        throw new SendOtpException();
    }

    @Override
    public boolean isSupportedOtpChannel(String channel) {
        return channel != null && otpChannels.contains(channel.toLowerCase());
    }

    @Override
    public List<KycSigningCertificateData> getAllKycSigningCertificates() throws KycSigningCertificateException {
    	//Since this wrapper itself signs kyc with its MISP certificate it is not needed to return anything here
    	return List.of();
    }
}