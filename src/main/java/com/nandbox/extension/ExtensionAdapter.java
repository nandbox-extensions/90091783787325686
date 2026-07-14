package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.data.*;
import com.nandbox.bots.api.inmessages.*;
import net.minidev.json.JSONObject;

public abstract class ExtensionAdapter implements Nandbox.Callback {
    @Override public void onConnect(Nandbox.Api api) {}
    @Override public void onReceive(IncomingMessage incomingMsg) {}
    @Override public void onReceive(JSONObject obj) {}
    @Override public void onClose() {}
    @Override public void onError() {}
    @Override public void onChatMenuCallBack(ChatMenuCallback chatMenuCallback) {}
    @Override public void onInlineMessageCallback(InlineMessageCallback inlineMsgCallback) {}
    @Override public void onMessagAckCallback(MessageAck msgAck) {}
    @Override public void onUserJoinedBot(User user) {}
    @Override public void onChatMember(ChatMember chatMember) {}
    @Override public void onChatAdministrators(ChatAdministrators chatAdministrators) {}
    @Override public void userStartedBot(User user) {}
    @Override public void onMyProfile(User user) {}
    @Override public void onProductDetail(ProductItemResponse productItem) {}
    @Override public void onCollectionProduct(GetProductCollectionResponse collectionProduct) {}
    @Override public void listCollectionItemResponse(ListCollectionItemResponse collections) {}
    @Override public void onUserDetails(User user, String appId) {}
    @Override public void userStoppedBot(User user) {}
    @Override public void userLeftBot(User user) {}
    @Override public void permanentUrl(PermanentUrl permenantUrl) {}
    @Override public void onChatDetails(Chat chat, String appId) {}
    @Override public void onInlineSearh(InlineSearch inlineSearch) {}
    @Override public void onBlackListPattern(Pattern pattern) {}
    @Override public void onWhiteListPattern(Pattern pattern) {}
    @Override public void onBlackList(BlackList blackList) {}
    @Override public void onDeleteBlackList(List_ak blackList) {}
    @Override public void onWhiteList(WhiteList whiteList) {}
    @Override public void onDeleteWhiteList(List_ak whiteList) {}
    @Override public void onScheduleMessage(IncomingMessage incomingScheduleMsg) {}
    @Override public void onWorkflowDetails(WorkflowDetails workflowDetails) {}
    @Override public void onCreateChat(Chat chat) {}
    @Override public void onMenuCallBack(MenuCallback menuCallback) {}
    @Override public void onExtensionDocResponse(ExtensionDocResponse extensionDocResponse) {}
    @Override public void onPaymentAuthorizationRequest(PaymentRequest paymentRequest) {}
    @Override public void onWebhookEvent(WebhookBody webhookBody) {}
}
