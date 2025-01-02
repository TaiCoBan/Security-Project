package com.utc.securityproject.service.impl;


import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.utc.securityproject.dto.request.AuthenticationRequest;
import com.utc.securityproject.dto.request.IntrospectRequest;
import com.utc.securityproject.dto.request.LogoutRequest;
import com.utc.securityproject.dto.request.RefreshRequest;
import com.utc.securityproject.dto.response.AuthenticationResponse;
import com.utc.securityproject.dto.response.IntrospectResponse;
import com.utc.securityproject.entity.Account;
import com.utc.securityproject.entity.InvalidatedToken;
import com.utc.securityproject.exception.AppException;
import com.utc.securityproject.exception.ErrorCode;
import com.utc.securityproject.repository.AccountRepository;
import com.utc.securityproject.repository.InvalidatedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.StringJoiner;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {
  private final AccountRepository accountRepository;
  private final InvalidatedTokenRepository invalidatedTokenRepository;
  
  @NonFinal
  @Value("${jwt.signerKey}")
  protected String SIGNER_KEY;
  
  @NonFinal
  @Value("${jwt.valid-duration}")
  protected long VALID_DURATION;
  
  @NonFinal
  @Value("${jwt.refreshable-duration}")
  protected long REFRESHABLE_DURATION;
  
  public IntrospectResponse introspect(IntrospectRequest request) throws JOSEException, ParseException {
    var token = request.getToken();
    boolean isValid = true;
    
    try {
      verifyToken(token, false);
    } catch (AppException e) {
      isValid = false;
    }
    
    return IntrospectResponse.builder().valid(isValid).build();
  }
  
  public AuthenticationResponse authenticate(AuthenticationRequest request) {
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
    var account = accountRepository
                       .findByEmail(request.getEmail())
                       .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    
    boolean authenticated = passwordEncoder.matches(request.getPassword(), account.getPassword());
    
    if (!authenticated) throw new AppException(ErrorCode.UNAUTHENTICATED);
    
    var token = generateToken(account);
    
    return AuthenticationResponse.builder().token(token).authenticated(true).build();
  }
  
  public void logout(LogoutRequest request) throws ParseException, JOSEException {
    try {
      var signToken = verifyToken(request.getToken(), true);

      String jit = signToken.getJWTClaimsSet().getJWTID();
      Date expiryTime = signToken.getJWTClaimsSet().getExpirationTime();

      InvalidatedToken invalidatedToken =
              InvalidatedToken.builder().id(jit).expiryTime(expiryTime).build();

      invalidatedTokenRepository.save(invalidatedToken);
    } catch (AppException exception) {
      log.info("Token already expired");
    }
  }
  
  public AuthenticationResponse refreshToken(RefreshRequest request) throws ParseException, JOSEException {
    var signedJWT = verifyToken(request.getToken(), true);

    var jit = signedJWT.getJWTClaimsSet().getJWTID();
    var expiryTime = signedJWT.getJWTClaimsSet().getExpirationTime();

    InvalidatedToken invalidatedToken =
            InvalidatedToken.builder().id(jit).expiryTime(expiryTime).build();

    invalidatedTokenRepository.save(invalidatedToken);

    var username = signedJWT.getJWTClaimsSet().getSubject();

    var account =
            accountRepository.findByUsername(username).orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));

    var token = generateToken(account);

    return AuthenticationResponse.builder().token(token).authenticated(true).build();
  }
  
  private String generateToken(Account account) {
    JWSHeader header = new JWSHeader(JWSAlgorithm.HS512);
    
    JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                                        .subject(account.getEmail())
                                        .issuer("LDTT")
                                        .issueTime(new Date())
                                        .expirationTime(new Date(
                                                Instant.now().plus(VALID_DURATION, ChronoUnit.SECONDS).toEpochMilli()))
                                        .jwtID(UUID.randomUUID().toString())
                                        .claim("scope", buildScope(account))
                                        .build();
    
    Payload payload = new Payload(jwtClaimsSet.toJSONObject());
    
    JWSObject jwsObject = new JWSObject(header, payload);
    
    try {
      jwsObject.sign(new MACSigner(SIGNER_KEY.getBytes()));
      return jwsObject.serialize();
    } catch (JOSEException e) {
      log.error("Cannot create token", e);
      throw new RuntimeException(e);
    }
  }
  
  private SignedJWT verifyToken(String token, boolean isRefresh) throws JOSEException, ParseException {
    JWSVerifier verifier = new MACVerifier(SIGNER_KEY.getBytes());

    SignedJWT signedJWT = SignedJWT.parse(token);

    Date expiryTime = (isRefresh)
                              ? new Date(signedJWT
                                                 .getJWTClaimsSet()
                                                 .getIssueTime()
                                                 .toInstant()
                                                 .plus(REFRESHABLE_DURATION, ChronoUnit.SECONDS)
                                                 .toEpochMilli())
                              : signedJWT.getJWTClaimsSet().getExpirationTime();

    var verified = signedJWT.verify(verifier);

    if (!(verified && expiryTime.after(new Date()))) throw new AppException(ErrorCode.UNAUTHENTICATED);

    if (invalidatedTokenRepository.existsById(signedJWT.getJWTClaimsSet().getJWTID()))
      throw new AppException(ErrorCode.UNAUTHENTICATED);

    return signedJWT;
  }
  
  private String buildScope(Account account) {
    StringJoiner stringJoiner = new StringJoiner(" ");

    if (!CollectionUtils.isEmpty(account.getRoles()))
      account.getRoles().forEach(role -> {
        stringJoiner.add("ROLE_" + role.getName());
        if (!CollectionUtils.isEmpty(role.getPermissions()))
          role.getPermissions().forEach(permission -> stringJoiner.add(permission.getName()));
      });

    return stringJoiner.toString();
  }
}

