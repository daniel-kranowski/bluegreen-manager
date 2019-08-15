package bluegreen.manager.model.dao;

import java.util.ArrayList;
import java.util.List;
import javax.persistence.Query;

import org.springframework.stereotype.Repository;

import bluegreen.manager.model.domain.Environment;

@Repository
public class EnvironmentDAO extends GenericDAO<Environment>
{
  /**
   * Returns the single named environment.  Throws if not found.
   */
  public Environment findNamedEnv(String envName)
  {
    if (envName == null)
    {
      return null;
    }
    Query query = findNamedEnvQuery(envName);
    return (Environment) query.getSingleResult();
  }

  /**
   * Returns the single named environment.  Null if not found.
   */
  public Environment findNamedEnvAllowNull(String envName)
  {
    if (envName == null)
    {
      return null;
    }
    Query query = findNamedEnvQuery(envName);
    List list = query.getResultList();
    if (list != null && list.size() > 0)
    {
      return (Environment) list.get(0);
    }
    return null; //Not found
  }

  /**
   * Makes (but does not run) a query for a named env.
   */
  private Query findNamedEnvQuery(String envName)
  {
    String queryString = "SELECT e FROM " + Environment.class.getSimpleName() + " e WHERE "
        + "e.envName = :envName";
    Query query = entityManager.createQuery(queryString);
    query.setParameter("envName", envName);
    return query;
  }

  /**
   * Returns a list of the named environments (assuming they exist).
   */
  public List<Environment> findNamedEnvs(String... envNames)
  {
    if (envNames == null)
    {
      return null;
    }
    else if (envNames.length == 0)
    {
      return new ArrayList<Environment>();
    }
    String queryString = "SELECT e FROM " + Environment.class.getSimpleName() + " e WHERE "
        + "e.envName IN (" + joinSqlQuotedStrings(envNames) + ")";
    return entityManager.createQuery(queryString).getResultList();
  }

  /**
   * Converts the input list of strings to one comma-delimited string with individual tokens quoted with sql apostrophes.
   */
  String joinSqlQuotedStrings(String[] strings)
  {
    if (strings == null)
    {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (String string : strings)
    {
      if (sb.length() > 0)
      {
        sb.append(", ");
      }
      sb.append("'" + string + "'");
    }
    return sb.toString();
  }

  /**
   * Returns all environments.
   */
  public List<Environment> findAll()
  {
    return entityManager.createQuery("SELECT OBJECT(e) FROM " + Environment.class.getSimpleName() + " as e").getResultList();
  }

}
