<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=false; section>
    <#if section = "header">
        휴대폰 인증
    <#elseif section = "form">
        <form id="kc-phone-mfa-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="code" class="${properties.kcLabelClass!}">문자로 받은 인증 코드를 입력하세요</label>
                <input id="code" name="code" type="text" inputmode="numeric" autocomplete="off" autofocus
                       class="${properties.kcInputClass!}" />
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       type="submit" value="확인" />
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="resend" value="true">코드 재발송</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
