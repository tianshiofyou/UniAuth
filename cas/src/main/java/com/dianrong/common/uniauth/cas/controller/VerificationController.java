package com.dianrong.common.uniauth.cas.controller;

import com.dianrong.common.uniauth.cas.controller.support.VerificationMessageType;
import com.dianrong.common.uniauth.cas.controller.support.VerificationMessagesTranslator;
import com.dianrong.common.uniauth.cas.model.IdentityExpiredSessionObj;
import com.dianrong.common.uniauth.cas.service.UserInfoManageService;
import com.dianrong.common.uniauth.cas.util.UniBundleUtil;
import com.dianrong.common.uniauth.cas.util.WebScopeUtil;
import com.dianrong.common.uniauth.common.bean.Info;
import com.dianrong.common.uniauth.common.bean.InfoName;
import com.dianrong.common.uniauth.common.bean.Response;
import com.dianrong.common.uniauth.common.bean.dto.UserDto;
import com.dianrong.common.uniauth.common.cons.AppConstants;
import com.dianrong.common.uniauth.common.util.StringUtil;
import com.dianrong.common.uniauth.sharerw.notification.EmailNotification;
import com.dianrong.common.uniauth.sharerw.notification.SmsNotification;
import com.dianrong.common.uniauth.sharerw.notification.exp.NotificationNotAvailableException;
import com.dianrong.common.uniauth.sharerw.notification.exp.SendNotificationFailedException;
import com.google.code.kaptcha.Constants;
import com.google.code.kaptcha.Producer;
import com.google.code.kaptcha.impl.WaterRipple;
import com.google.code.kaptcha.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * 验证相关的controller.
 *
 * @author wanglin
 */
@Slf4j
@Controller
@RequestMapping("verification")
public class VerificationController {

  // 邮箱或者短信验证码的长度
  private static final int VERIFICATION_LENGTH = 6;

  @Autowired
  private SmsNotification smsNotify;

  @Autowired
  private EmailNotification emailNotify;

  @Autowired private UserInfoManageService userInfoService;

  @Autowired
  private MessageSource messageSource;

  @Resource(name = "uniauthConfig")
  private Map<String, String> allZkNodeMap;

  private Producer captchaProducer;

  /**
   * 初始化验证码.
   */
  @PostConstruct
  public void initCaptcha() {
    Properties props = new Properties();
    props.put(Constants.KAPTCHA_BORDER, "no");
    props.put(Constants.KAPTCHA_TEXTPRODUCER_CHAR_STRING, "02345689");
    props.put(Constants.KAPTCHA_BACKGROUND_CLR_TO, Color.WHITE);
    props.put(Constants.KAPTCHA_BACKGROUND_CLR_FROM, Color.WHITE);
    props.put(Constants.KAPTCHA_OBSCURIFICATOR_IMPL, WaterRipple.class.getName());
    props.put(Constants.KAPTCHA_NOISE_COLOR, Color.BLACK);
    Config config = new Config(props);
    captchaProducer = config.getProducerImpl();
  }

  /**
   * 根据session中的identity来发送邮件.
   *
   * @param captcha 验证码.
   */
  @ResponseBody
  @RequestMapping(value = "send/session", method = RequestMethod.POST)
  public Response<String> sendVerification(HttpServletRequest request, HttpServletResponse response,
      @RequestParam(value = "captcha", required = true) String captcha,
      @RequestParam(value = "type", required = false) String type) {
    String identity = WebScopeUtil.getIdentity(request.getSession());
    if (!StringUtils.hasText(identity)) {
      return Response.failure(Info.build(InfoName.IDENTITY_REQUIRED, "lack of identity"));
    } else {
      return innerSendVerification(request, response, identity, null, captcha, type, false);
    }
  }

  /**
   * 发送短信或邮件验证码.
   *
   * @param identity 身份标识信息.
   * @param captcha 验证码.
   */
  @ResponseBody
  @RequestMapping(value = "send", method = RequestMethod.POST)
  public Response<String> sendVerification(HttpServletRequest request, HttpServletResponse response,
      @RequestParam(value = "identity", required = true) String identity,
      @RequestParam(value = "tenancy_code", required = true) String tenancyCode,
      @RequestParam(value = "captcha", required = true) String captcha,
      @RequestParam(value = "type", required = false) String type) {
    return innerSendVerification(request, response, identity, tenancyCode, captcha, type, true);
  }

  /**
   * 发送短信验证码的实现.
   */
  private Response<String> innerSendVerification(HttpServletRequest request,
      HttpServletResponse response, String identity, String tenancyCode, String captcha,
      String type, boolean checkIdentity) {
    if (!WebScopeUtil.checkCaptchaFromSession(request.getSession(), captcha)) {
      // 验证码不对.
      return Response.failure(Info.build(InfoName.VALIDATE_FAIL, UniBundleUtil.getMsg(messageSource,
          "verification.controller.verification.captcha.failed")));
    }
    // 是否验证身份信息.
    if (checkIdentity) {
      try {
        UserDto userDto = userInfoService.getUserDetailInfo(identity, tenancyCode);
        if (userDto == null) {
          return Response.failure(Info.build(InfoName.VALIDATE_FAIL, UniBundleUtil
              .getMsg(messageSource, "verification.controller.verification.identity.not.exist",
                  identity)));
        }
      } catch (Exception e) {
        log.error("Failed get userInfo", e);
        return Response.failure(Info.build(InfoName.VALIDATE_FAIL, UniBundleUtil
            .getMsg(messageSource, "verification.controller.verification.identity.not.exist",
                identity)));
      }
    }
    String verification = getVerificationNumber();
    VerificationMessageType messageType = VerificationMessageType.getType(type);
    String verifyMsg = VerificationMessagesTranslator
        .getContent(messageType, messageSource, verification, messageType.getEffectiveMinutes());
    long effectiveMillis = messageType.getEffectiveMinutes() * 60L * 1000L;
    // Send email
    if (StringUtil.isEmailAddress(identity)) {
      try {
        String subject = VerificationMessagesTranslator.getTitle(messageType, messageSource);
        emailNotify.send(subject, identity, verifyMsg);
      } catch (NotificationNotAvailableException e1) {
        log.warn("send email is not available", e1);
        return Response.failure(Info.build(InfoName.INTERNAL_ERROR, UniBundleUtil.getMsg(messageSource, "verification.controller.verification.not.available", "Email")));
      } catch (SendNotificationFailedException e2) {
        log.warn("send email failed", e2);
        return Response.failure(Info.build(InfoName.INTERNAL_ERROR, UniBundleUtil.getMsg(messageSource, "verification.controller.verification.send.failed", "Email")));
      }
      // after sending successfully, set flag to session
      WebScopeUtil.putEmailVerificationToSession(request.getSession(), IdentityExpiredSessionObj.build(verification, effectiveMillis, identity));
      return Response.success(showVerifyCode() ? verification : "");
    }

    // send short message
    if (StringUtil.isPhoneNumber(identity)) {
      try {
        smsNotify.send(identity, verifyMsg);
      } catch (NotificationNotAvailableException e1) {
        log.warn("send sms is not available", e1);
        return Response.failure(Info.build(InfoName.INTERNAL_ERROR, UniBundleUtil.getMsg(messageSource, "verification.controller.verification.not.available", "SMS")));
      } catch (SendNotificationFailedException e2) {
        log.warn("send sms failed", e2);
        return Response.failure(Info.build(InfoName.INTERNAL_ERROR, UniBundleUtil.getMsg(messageSource, "verification.controller.verification.send.failed", "SMS")));
      }
      // After sending successfully, set flag to session
      WebScopeUtil.putSmsVerificationToSession(request.getSession(), IdentityExpiredSessionObj
          .build(verification, effectiveMillis, identity));
      return Response.success(showVerifyCode() ? verification : "");
    }

    // error
    log.error(String.format("%s is not a valid email address or phone number", identity));
    return Response.failure(Info.build(InfoName.BAD_REQUEST, UniBundleUtil.getMsg(messageSource,
        "verification.controller.verification.invalid.identity", identity)));
  }



  /**
   * 根据session中的identity来验证验证码.
   */
  @ResponseBody
  @RequestMapping(value = "verify/session", method = RequestMethod.POST)
  public Response<Void> checkVerification(HttpServletRequest request, HttpServletResponse response,
      @RequestParam(value = "verifyCode", required = true) String verifyCode) {
    String identity = WebScopeUtil.getIdentity(request.getSession());
    if (!StringUtils.hasText(identity)) {
      return Response.failure(Info.build(InfoName.IDENTITY_REQUIRED, "lack of identity"));
    } else {
      return checkVerification(request, response, identity, verifyCode);
    }
  }


  /**
   * 验证短信或邮件验证码.
   */
  @ResponseBody
  @RequestMapping(value = "verify", method = RequestMethod.POST)
  public Response<Void> checkVerification(HttpServletRequest request, HttpServletResponse response,
      @RequestParam(value = "identity", required = true) String identity,
      @RequestParam(value = "verifyCode", required = true) String verifyCode) {
    // email
    if (StringUtil.isEmailAddress(identity)) {
      IdentityExpiredSessionObj<String> obj =
          WebScopeUtil.getEmailVerificationFromSession(request.getSession());
      if (obj != null && !obj.isExpired()) {
        if (verifyCode.equals(obj.getContent()) && obj.getIdentity().equals(identity)) {
          // validation successfully

          // set flag
          WebScopeUtil.setVerificationChecked(request.getSession(), identity);

          // remove email verification
          WebScopeUtil.removeEmailVerification(request.getSession());
          return Response.success();
        }
      }
      // validation failed
      return Response.failure(Info.build(InfoName.BAD_REQUEST, UniBundleUtil.getMsg(messageSource,
          "verification.controller.verification.validate.failed")));
    }

    // short message
    if (StringUtil.isPhoneNumber(identity)) {
      IdentityExpiredSessionObj<String> obj =
          WebScopeUtil.getSmsVerificationFromSession(request.getSession());
      if (obj != null && !obj.isExpired()) {
        if (verifyCode.equals(obj.getContent()) && obj.getIdentity().equals(identity)) {
          // validation successfully

          // set flag
          WebScopeUtil.setVerificationChecked(request.getSession(), identity);

          // remove email verification
          WebScopeUtil.removeSmsVerification(request.getSession());
          return Response.success();
        }
      }
      // validation failed
      return Response.failure(Info.build(InfoName.BAD_REQUEST, UniBundleUtil.getMsg(messageSource,
          "verification.controller.verification.validate.failed")));
    }

    // error
    log.error(String.format("%s is not a valid email address or phone number", identity));
    return Response.failure(Info.build(InfoName.BAD_REQUEST, UniBundleUtil.getMsg(messageSource,
        "verification.controller.verification.invalid.identity", identity)));
  }


  /**
   * Write Captcha to outputStream.
   */
  @RequestMapping("captcha")
  public void captcha(HttpServletRequest request, HttpServletResponse response) {
    String capText = StringUtil.generateCaptchaStr(4);
    WebScopeUtil.putCaptchaToSession(request.getSession(), capText);
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Cache-Control", "no-cache");
    response.setContentType("image/jpeg");
    try {
      BufferedImage bi = captchaProducer.createImage(capText);
      ImageIO.write(bi, "jpg", response.getOutputStream());
    } catch (IOException e) {
      log.warn("captcha creation failed", e);
      try {
        response.sendError(500, "captcha creation failed");
      } catch (IOException e1) {
        log.warn("captcha creation failed", e1);
      }
    }
  }

  // 生成数字验证码.
  private String getVerificationNumber() {
    return StringUtil.generateNumberStr(VERIFICATION_LENGTH);
  }

  /**
   * 判断是否将短信或者邮箱验证码返回到前端.
   */
  private boolean showVerifyCode() {
    return Boolean.TRUE.toString()
        .equalsIgnoreCase(allZkNodeMap.get(AppConstants.ZK_CAS_VERIFY_CODE_SHOW));
  }
}
