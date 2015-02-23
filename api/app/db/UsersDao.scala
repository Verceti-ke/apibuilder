package db

import com.gilt.apidoc.v0.models.{Error, User, UserForm}
import lib.{Constants, Role, Validation}
import anorm._
import play.api.db._
import play.api.Play.current
import play.api.libs.json._
import java.util.UUID
import scala.util.{Failure, Success, Try}

object UsersDao {

  val AdminUserEmail = "admin@apidoc.me"

  private val BaseQuery = """
    select guid, email, name
      from users
     where deleted_at is null
  """

  private val InsertQuery = """
    insert into users
    (guid, email, name, created_by_guid, updated_by_guid)
    values
    ({guid}::uuid, {email}, {name}, {created_by_guid}::uuid, {updated_by_guid}::uuid)
  """

  private val UpdateQuery = """
  update users
     set email = {email},
         name = {name}
   where guid = {guid}
  """

  def validate(
    form: UserForm,
    existingUser: Option[User] = None
  ): Seq[Error] = {
    val emailErrors = findByEmail(form.email) match {
      case None => Seq.empty
      case Some(u) => {
        if (existingUser.map(_.guid) == Some(u.guid)) {
          Seq.empty
        } else {
          Seq("User with this email address already exists")
        }
      }
    }

    val passwordErrors = UserPasswordsDao.validate(form.password).map(_.message)

    Validation.errors(emailErrors ++ passwordErrors)
  }

  def update(updatingUser: User, user: User, form: UserForm) {
    DB.withConnection { implicit c =>
      SQL(UpdateQuery).on('guid -> user.guid,
                          'email -> form.email,
                          'name -> form.name,
                          'updated_by_guid -> updatingUser.guid).execute()
    }

    if (user.email.trim.toLowerCase != form.email.trim.toLowerCase) {
      EmailVerificationsDao.upsert(updatingUser, user, form.email)
    }

  }

  def create(form: UserForm): User = {
    val guid = UUID.randomUUID
    DB.withTransaction { implicit c =>
      SQL(InsertQuery).on('guid -> guid,
                          'email -> form.email.trim,
                          'name -> form.name.map(_.trim),
                          'created_by_guid -> Constants.DefaultUserGuid,
                          'updated_by_guid -> Constants.DefaultUserGuid).execute()

      UserPasswordsDao.doCreate(c, guid, guid, form.password)
    }

    val user = findByGuid(guid).getOrElse {
      sys.error("Failed to create user")
    }

    global.Actors.mainActor ! actors.MainActor.Messages.UserCreated(guid)

    user
  }

  def findByToken(token: String): Option[User] = {
    findAll(token = Some(token)).headOption
  }

  def findByEmail(email: String): Option[User] = {
    findAll(email = Some(email)).headOption
  }

  def findByGuid(guid: String): Option[User] = {
    findAll(guid = Some(guid)).headOption
  }

  def findByGuid(guid: UUID): Option[User] = {
    findByGuid(guid.toString)
  }

  def findAll(guid: Option[String] = None,
              email: Option[String] = None,
              token: Option[String] = None): Seq[User] = {
    require(!guid.isEmpty || !email.isEmpty || !token.isEmpty, "Must have either a guid, email or token")

    val sql = Seq(
      Some(BaseQuery.trim),
      guid.map { v =>
        Try(UUID.fromString(v)) match {
          case Success(uuid) => "and users.guid = {guid}::uuid"
          case Failure(e) => e match {
            case e: IllegalArgumentException => "and false"
          }
        }
      },
      guid.map { v => "and users.guid = {guid}::uuid" },
      email.map { v => "and users.email = trim(lower({email}))" },
      token.map { v => "and users.guid = (select user_guid from tokens where token = {token} and deleted_at is null)"},
      Some("limit 1")
    ).flatten.mkString("\n   ")

    val bind = Seq[Option[NamedParameter]](
      guid.map('guid -> _),
      email.map('email -> _),
      token.map('token ->_)
    ).flatten

    DB.withConnection { implicit c =>
      SQL(sql).on(bind: _*)().toList.map { fromRow(_) }.toSeq
    }
  }

  private[db] def fromRow(
    row: anorm.Row,
    prefix: Option[String] = None
  ) = {
    val p = prefix.map( _ + "_").getOrElse("")
    User(
      guid = row[UUID](s"${p}guid"),
      email = row[String](s"${p}email"),
      name = row[Option[String]](s"${p}name")
    )
  }

}
