package bluegreen.manager.utils;

public class HashUtil
{
  private HashUtil()
  {
    //Do not instantiate me
  }

  /**
   * Generates a jdk hashcode from a long value.
   */
  public static int hashId(long id)
  {
    final int prime = 31;
    int hash = 17;
    hash = hash * prime + ((int) (id ^ (id >>> 32)));
    return hash;
  }
}
