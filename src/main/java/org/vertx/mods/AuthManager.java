package org.vertx.mods;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

public class AuthManager extends BusModBase {
	private static final String ERROR_INVALID_DATA = "invalid data";
	private static final String ERROR_NOT_MATCHED = "not matched";

	private String thisAddress;
	private String userCollection;
	private String tokenCollection;
	private String persistorAddress;

	public void start() {
		super.start();
		this.thisAddress = getOptionalStringConfig("this_address", "vertx.auth");
		this.persistorAddress = getOptionalStringConfig("persistor_address", "vertx.mongo");
		this.userCollection = getOptionalStringConfig("user_collection", "users");
		this.tokenCollection = getOptionalStringConfig("token_collection", "tokens");

		eb.registerHandler(thisAddress, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> message) {
				switch (message.body().getString("action")) {
				case "login":
					login(message);
					break;
				case "logout":
					logout(message);
					break;
				case "authorise":
					authorise(message);
					break;
				}
			}
		});
	}

	protected void login(final Message<JsonObject> message) {
		final String userid = getMandatoryString("userid", message);
		String password = getMandatoryString("password", message);
		if (userid == null || password == null) {
			message.reply(errorMessage(ERROR_INVALID_DATA));
			return;
		}

		password = PasswordManager.encodeString(password);

		eb.send(persistorAddress, MongoUtil.findOneConfig(userCollection, new JsonObject().putString("userid", userid).putString("password", password)), new Handler<Message<JsonObject>>() {
			public void handle(final Message<JsonObject> findUserResult) {
				if (findUserResult.body().getString("status").equals("error")) {
					message.reply(findUserResult.body());
					return;
				}
				if (findUserResult.body().getObject("result") == null) {
					message.reply(errorMessage(ERROR_NOT_MATCHED));
					return;
				}
				String user_id = findUserResult.body().getObject("result").getString("_id");
				eb.send(persistorAddress, MongoUtil.findOneConfig(tokenCollection, new JsonObject().putString("user_id", user_id)), new Handler<Message<JsonObject>>() {
					@Override
					public void handle(Message<JsonObject> findAlreadyResult) {
						if (findAlreadyResult.body().getString("status").equals("error")) {
							message.reply(findAlreadyResult.body());
							return;
						}
						if (findAlreadyResult.body().getObject("result") != null) { // token data exist
							String token = findAlreadyResult.body().getObject("result").getString("_id");
							message.reply(new JsonObject().putString("token", token).putString("status", "ok"));
						} else {
							JsonObject document = new JsonObject().putString("user_id", findUserResult.body().getObject("result").getString("_id"));
							eb.send(persistorAddress, MongoUtil.saveConfig(tokenCollection, document), new Handler<Message<JsonObject>>() {
								@Override
								public void handle(Message<JsonObject> saveTokenResult) {
									if (saveTokenResult.body().getString("status").equals("error")) {
										message.reply(saveTokenResult.body());
										return;
									}
									String token = saveTokenResult.body().getString("_id");
									message.reply(new JsonObject().putString("token", token).putString("status", "ok"));
								}
							});
						}
					}
				});
			}
		});
	}

	protected void logout(final Message<JsonObject> message) {
		String token = getMandatoryString("token", message);
		if (token == null) {
			message.reply(errorMessage(ERROR_INVALID_DATA));
			return;
		}
		JsonObject matcher = new JsonObject().putString("_id", token);
		eb.send(persistorAddress, MongoUtil.deleteConfig(tokenCollection, matcher), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> deleteTokenResult) {
				if (deleteTokenResult.body().getString("status").equals("error")) {
					message.reply(deleteTokenResult.body());
				}
				if (deleteTokenResult.body().getNumber("number").equals(0)) {
					message.reply(errorMessage(ERROR_NOT_MATCHED));
					return;
				}
				message.reply(new JsonObject().putString("status", "ok"));
			}
		});
	}

	protected void authorise(final Message<JsonObject> message) {
		String token = getMandatoryString("token", message);
		if (token == null) {
			message.reply(errorMessage(ERROR_INVALID_DATA));
			return;
		}

		eb.send(persistorAddress, MongoUtil.findOneConfig(tokenCollection, new JsonObject().putString("_id", token)), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> findTokenResult) {
				if (findTokenResult.body().getString("status").equals("error")) {
					message.reply(findTokenResult.body());
					return;
				}
				if (findTokenResult.body().getObject("result") == null) {
					message.reply(errorMessage(ERROR_NOT_MATCHED));
					return;
				}
				message.reply(new JsonObject().putString("status", "ok").putString("user_id", findTokenResult.body().getObject("result").getString("user_id")));
			}
		});
	}

	private JsonObject errorMessage(String errorMessage) {
		JsonObject jsonErrorMessage = new JsonObject();
		jsonErrorMessage.putString("status", "error");
		jsonErrorMessage.putString("message", errorMessage);
		return jsonErrorMessage;
	}
}
