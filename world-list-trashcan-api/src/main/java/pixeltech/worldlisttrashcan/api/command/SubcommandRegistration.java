package pixeltech.worldlisttrashcan.api.command;

/** 表示一个可注销的 /wtc 副指令注册。 */
public interface SubcommandRegistration extends AutoCloseable {

    /** 注销副指令；重复调用必须无副作用。 */
    @Override
    void close();
}
