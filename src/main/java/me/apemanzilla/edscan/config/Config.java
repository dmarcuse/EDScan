package me.apemanzilla.edscan.config;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.Property;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.NonFinal;

public class Config {
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Value
	@RequiredArgsConstructor
	public class Listener<T> {
		Type type;
		BiConsumer<T, T> consumer;

		public Listener(Class<T> cls, BiConsumer<T, T> consumer) {
			this((Type) cls, consumer);
		}
	}

	private final Map<String, JsonElement> data;

	private final Multimap<String, Listener<?>> listeners = Multimaps
			.synchronizedSetMultimap(MultimapBuilder.hashKeys().hashSetValues().build());

	public Config(Map<String, JsonElement> data) {
		this.data = new ConcurrentHashMap<>(data);
	}

	public Config() {
		this.data = new ConcurrentHashMap<>();
	}

	public boolean containsKey(String key) {
		return data.containsKey(key);
	}

	public Optional<JsonElement> getRaw(String key) {
		return Optional.ofNullable(data.get(key));
	}

	public <T> Optional<T> getAs(String key, Type type) {
		return getRaw(key).map(j -> gson.fromJson(j, type));
	}

	public <T> Optional<T> getAs(String key, Class<T> cls) {
		return getAs(key, (Type) cls);
	}

	@SuppressWarnings("unchecked")
	private void invokeListeners(String key, JsonElement from, JsonElement to) {
		listeners.get(key).forEach(l -> {
			Object o1 = gson.fromJson(from, l.type);
			Object o2 = gson.fromJson(to, l.type);

			((Listener<Object>) l).consumer.accept(o1, o2);
		});
	}

	public void remove(String key) {
		JsonElement old = getRaw(key).orElse(null);
		data.remove(key);

		invokeListeners(key, old, null);
	}

	public void putRaw(String key, JsonElement json) {
		JsonElement old = getRaw(key).orElse(null);
		data.put(key, json);

		invokeListeners(key, old, json);
	}

	public <T> void put(String key, T value) {
		putRaw(key, gson.toJsonTree(value));
	}

	@Value
	@RequiredArgsConstructor
	public class ConfigProperty<T> implements Property<T> {
		Type type;
		String key;

		ArrayList<ChangeListener<? super T>> changeListeners = new ArrayList<>();
		ArrayList<InvalidationListener> invalidationListeners = new ArrayList<>();

		@Override
		public Object getBean() {
			return null;
		}

		@Override
		public String getName() {
			return key;
		}

		@Override
		public T getValue() {
			return Config.this.<T>getAs(key, type).orElse(null);
		}

		@Override
		public void setValue(T value) {
			put(key, value);
		}

		@Override
		public void addListener(ChangeListener<? super T> listener) {
			// TODO Auto-generated method stub

		}

		@Override
		public void removeListener(ChangeListener<? super T> listener) {
			// TODO Auto-generated method stub

		}

		@Override
		public void addListener(InvalidationListener listener) {
			// TODO Auto-generated method stub

		}

		@Override
		public void removeListener(InvalidationListener listener) {
			// TODO Auto-generated method stub

		}

		@NonFinal
		private ObservableValue<? extends T> bindTarget;

		@NonFinal
		private ChangeListener<T> bindListener;

		@Override
		public void bind(ObservableValue<? extends T> observable) {
			bindTarget = observable;
			bindListener = (s, o, n) -> setValue(n);

			bindTarget.addListener(bindListener);
		}

		@Override
		public void unbind() {
			if (isBound()) {
				bindTarget.removeListener(bindListener);

				bindTarget = null;
				bindListener = null;
			}
		}

		@Override
		public boolean isBound() {
			return bindTarget != null;
		}

		@Override
		public void bindBidirectional(Property<T> other) {
			Bindings.bindBidirectional(this, other);
		}

		@Override
		public void unbindBidirectional(Property<T> other) {
			Bindings.unbindBidirectional(this, other);
		}
	}
}
